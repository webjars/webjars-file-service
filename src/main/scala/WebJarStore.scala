import com.jamesward.zio_mavencentral.MavenCentral
import com.jamesward.zio_mavencentral.MavenCentral.{GroupArtifactVersion, JarMeta, MavenCentralRepo, NotFoundError}
import zio.*
import zio.http.{Body, Client, Header, MediaType}
import zio.schema.{Schema, derived}
import zio.stream.ZStream

import java.io.{File, IOException}
import java.time.ZonedDateTime
import java.util.UUID
import java.util.zip.ZipException

/** Where a webjar's files are read from. */
enum WebJarSource:
  /**
   * Fully extracted into the object store. `versionedLayout` is whether the
   * webjar's files live under `<artifactId>/<version>/` (most do), so the
   * common case costs one object-store request per file.
   */
  case Stored(gav: GroupArtifactVersion, jarMeta: JarMeta, versionedLayout: Boolean, store: ObjectStore)
  /** Staged on local disk while uploading; reads after it is retired go to the object store. */
  case Local(jar: StagedJar, store: ObjectStore)

  def webJar: GroupArtifactVersion =
    this match
      case Stored(gav, _, _, _) => gav
      case Local(jar, _)        => jar.gav

  def meta: JarMeta =
    this match
      case Stored(_, jarMeta, _, _) => jarMeta
      case Local(jar, _)            => jar.meta

  /** The same webjar read from the object store. */
  private def stored: WebJarSource =
    this match
      case s: Stored         => s
      case Local(jar, store) => Stored(jar.gav, jar.meta, versionedLayout = true, store)

  /**
   * The jar entries a request path can map to, most likely first: webjars
   * put files either under `<artifactId>/<version>/` or directly under
   * `<artifactId>/`.
   */
  def candidates(file: String): List[String] =
    val withVersion    = s"${WebJarStore.webJarsPathPrefix}/${webJar.artifactId}/${webJar.version}/$file"
    val withoutVersion = s"${WebJarStore.webJarsPathPrefix}/${webJar.artifactId}/$file"
    this match
      case Stored(_, _, false, _) => List(withoutVersion, withVersion)
      case _                      => List(withVersion, withoutVersion)

  /** A jar entry's bytes, or `None` if it doesn't exist. The stream is valid while the scope is open. */
  def open(entryName: String): ZIO[Scope, Throwable, Option[ZStream[Any, Throwable, Byte]]] =
    this match
      case Stored(gav, _, _, store) =>
        store.get(WebJarStore.fileKey(gav, entryName)).map(_.map(_.body))
      case Local(jar, _) =>
        jar.hasEntry(entryName)
          .map(Option.when(_)(jar.streamEntry(entryName).catchAll:
            case _: StagedJar.Retired => ZStream.unwrapScoped(stored.open(entryName).someOrFail(java.nio.file.NoSuchFileException(entryName)))
            case e: IOException       => ZStream.fail(e)
          ))
          .catchAll:
            case _: StagedJar.Retired => stored.open(entryName)
            case e: IOException       => ZIO.fail(e)

  /** Sorted names of the user-visible webjar files. */
  def fileNames: Task[Seq[String]] =
    this match
      case Stored(gav, _, _, store) =>
        WebJarStore.readManifest(store, gav).someOrFail(IllegalStateException(s"manifest missing for stored $gav")).map(_.files)
      case Local(jar, _) =>
        jar.entryNames(WebJarStore.isWebJarFile).map(_.sorted).catchAll:
          case _: StagedJar.Retired => stored.fileNames
          case e: IOException       => ZIO.fail(e)

/**
 * Per-GAV record written to the object store after every file of the
 * webjar has been uploaded. Its presence is what marks a GAV as stored, so
 * a partially-uploaded GAV (crash mid-upload) is simply re-populated.
 */
final case class Manifest(lastModified: Option[ZonedDateTime], etag: Option[String], files: List[String]) derives Schema

/** Errors fetching a GAV that isn't stored yet. */
final case class UploadsBusy(gav: GroupArtifactVersion)
final case class UpstreamCorrupt(gav: GroupArtifactVersion, sizeBytes: Long, reason: String)
final case class UpstreamUnavailable(gav: GroupArtifactVersion, reason: String)

type FetchError = NotFoundError | UpstreamCorrupt | UpstreamUnavailable

/**
 * Webjar file storage backed by an [[ObjectStore]].
 *
 * A request for a GAV that isn't stored yet takes an upload slot. A
 * background fiber owning the slot downloads the jar to local disk, the
 * request is served from that [[StagedJar]], and every webjar file is
 * uploaded to the object store. When the upload ends (manifest written, or
 * failure) the slot is released and the staged jar is retired, so local disk
 * holds at most `maxConcurrentUploads` jars. Requests for a GAV already in a
 * slot share it.
 *
 * Serving from a staged jar is cheap; the scarce resource is object-store
 * write throughput. So slots are plentiful and every file upload, across all
 * slots, takes one of `maxConcurrentFileUploads` permits around both reading
 * the entry and writing it. That bounds the bytes held in memory and the
 * blocking threads reading jars, no matter how many GAVs are uploading.
 *
 * A miss while every slot is in use fails with [[UploadsBusy]] rather than
 * queueing; stored GAVs are always served.
 *
 * `stored` remembers which GAVs are in the object store (with their cache
 * metadata) so each GAV costs at most one manifest read per process. It is
 * bounded by the size of the webjars catalog (~83k versions), so it needs no
 * eviction. `absent` remembers GAVs whose manifest was missing: this process
 * is the only writer, so they stay missing until it uploads them, and a
 * crawler retrying a busy GAV doesn't cost an object-store read per retry.
 */
final case class WebJarStore(
  objects: ObjectStore,
  stagingDir: File,
  maxConcurrentUploads: Int,
  filePermits: Semaphore,
  stored: Ref[Map[GroupArtifactVersion, WebJarStore.StoredGav]],
  absent: Ref[Set[GroupArtifactVersion]],
  slots: Ref[Map[GroupArtifactVersion, Promise[FetchError, StagedJar]]],
  staged: Ref[Map[File, Long]],
  busyRejections: Ref[Long],
):
  import WebJarStore.*

  /** The source for a GAV if it's available without taking a new upload slot. */
  def known(gav: GroupArtifactVersion): ZIO[Any, Nothing, Option[WebJarSource]] =
    stored.get.map(_.get(gav)).flatMap:
      case Some(s) => ZIO.some(s.source(gav, objects))
      case None =>
        slots.get.map(_.get(gav)).flatMap:
          case Some(staging) => staging.await.fold(_ => None, jar => Some(WebJarSource.Local(jar, objects)))
          case None =>
            absent.get.map(_.contains(gav)).flatMap: knownAbsent =>
              if knownAbsent then ZIO.none else fromManifest(gav)

  /**
   * Take an upload slot for `gav` (or join the one already holding it) and
   * wait for its jar to be staged.
   */
  def fetch(gav: GroupArtifactVersion): ZIO[Client & MavenCentralRepo, FetchError | UploadsBusy, WebJarSource] =
    stored.get.map(_.get(gav)).flatMap:
      case Some(s) => ZIO.succeed(s.source(gav, objects))
      case None =>
        Promise.make[FetchError, StagedJar].flatMap: mine =>
          slots.modify: m =>
            m.get(gav) match
              case Some(existing)                     => (Some(existing), m)
              case None if m.size >= maxConcurrentUploads => (None, m)
              case None                               => (Some(mine), m.updated(gav, mine))
          .flatMap:
            case None =>
              busyRejections.update(_ + 1) *> ZIO.fail(UploadsBusy(gav))
            case Some(staging) =>
              // The slot owner runs as a daemon so a requester being interrupted
              // can't leave the promise incomplete or the slot held.
              populate(gav, mine).forkDaemon.when(staging eq mine) *>
                staging.await.map(WebJarSource.Local(_, objects))

  def storedCount: UIO[Int]   = stored.get.map(_.size)
  def uploadingCount: UIO[Int] = slots.get.map(_.size)
  def stagedCount: UIO[Int]   = staged.get.map(_.size)
  def stagedBytes: UIO[Long]  = staged.get.map(_.values.sum)
  def busyCount: UIO[Long]    = busyRejections.get
  def absentCount: UIO[Int]   = absent.get.map(_.size)

  private def populate(gav: GroupArtifactVersion, staging: Promise[FetchError, StagedJar]): ZIO[Client & MavenCentralRepo, Nothing, Unit] =
    download(gav).exit
      .tap(staging.done(_))
      .flatMap:
        case Exit.Success(jar) =>
          upload(gav, jar)
            .catchAllCause(c => ZIO.logErrorCause(s"webjar upload failed gav=$gav", c))
            .ensuring(jar.retire)
        case Exit.Failure(cause) =>
          // Nonexistent GAVs (bad paths) can be requested without bound; don't remember them.
          absent.update(_ - gav).when(cause.failureOption.exists(_.isInstanceOf[NotFoundError]))
      .ensuring(slots.update(_ - gav))
      .unit

  private def download(gav: GroupArtifactVersion): ZIO[Client & MavenCentralRepo, FetchError, StagedJar] =
    // Unique per attempt: an earlier jar for the same GAV may still be open for readers.
    val file = File(stagingDir, s"${gav.toString.replace('/', '_')}-${UUID.randomUUID()}.jar")
    val attempt =
      for
        url      <- MavenCentral.jarUri(gav.groupId, gav.artifactId, gav.version).mapError:
                      case nf: NotFoundError => nf
                      case t: Throwable      => UpstreamUnavailable(gav, t.getMessage.nn)
        timed    <- MavenCentral.downloadJar(url, file).mapError(t => UpstreamUnavailable(gav, String.valueOf(t.getMessage))).timed
        size     <- ZIO.attemptBlockingIO(file.length()).orElseSucceed(0L)
        _        <- ZIO.logInfo(s"webjar_downloaded gav=$gav size_kb=${size / 1024} duration_ms=${timed._1.toMillis}")
        jar      <- StagedJar.open(gav, timed._2, file, staged.update(_ - file)).catchAll:
                      case e: ZipException =>
                        deleteFile(file) *> ZIO.fail(UpstreamCorrupt(gav, size, String.valueOf(e.getMessage)))
                      case e: IOException =>
                        deleteFile(file) *> ZIO.fail(UpstreamUnavailable(gav, String.valueOf(e.getMessage)))
        _        <- staged.update(_.updated(file, size))
      yield jar
    // A corrupt download is usually a truncated transfer; retry a couple of times.
    attempt.retry(
      Schedule.recurs(corruptRetries).whileInput[FetchError]:
        case _: UpstreamCorrupt => true
        case _                  => false
    )
      .tapError(e => deleteFile(file) *> ZIO.logWarning(s"webjar download failed gav=$gav error=$e"))

  private def deleteFile(file: File): UIO[Unit] =
    ZIO.attemptBlockingIO(file.delete()).ignoreLogged

  private def fromManifest(gav: GroupArtifactVersion): UIO[Option[WebJarSource]] =
    readManifest(objects, gav)
      .map(_.map(m => StoredGav(m.meta, versionedLayout(gav, m.files))))
      .tap:
        case Some(s) => stored.update(_.updated(gav, s))
        case None    => absent.update(a => (if a.size >= maxAbsent then Set.empty else a) + gav)
      .map(_.map(_.source(gav, objects)))
      // An object-store outage degrades to the download path rather than failing requests.
      .catchAllCause(c => ZIO.logWarningCause(s"manifest read failed gav=$gav", c).as(None))

  private def upload(gav: GroupArtifactVersion, jar: StagedJar): Task[Unit] =
    def local[A](op: IO[StagedJar.Retired | IOException, A]): Task[A] =
      op.mapError:
        case r: StagedJar.Retired => IllegalStateException(s"staged jar retired during upload: ${r.gav}")
        case e: IOException       => e
    for
      names    <- local(jar.entryNames(isWebJarFile)).map(_.sorted)
      duration <- ZIO.foreachParDiscard(names): name =>
                    filePermits.withPermit:
                      local(jar.readEntry(name))
                        .flatMap(bytes => objects.write(fileKey(gav, name), bytes, contentType(name)))
                  .withParallelism(perGavParallelism)
                  .timed
                  .map(_._1)
      manifest  = Manifest(jar.meta.maybeLastModified, jar.meta.maybeEtag.map(_.renderedValue), names)
      json     <- Body.json(manifest).asChunk
      _        <- objects.write(manifestKey(gav), json.toArray, Some(MediaType.application.json))
      _        <- stored.update(_.updated(gav, StoredGav(manifest.meta, versionedLayout(gav, names))))
      _        <- absent.update(_ - gav)
      _        <- ZIO.logInfo(s"webjar_uploaded gav=$gav files=${names.size} duration_ms=${duration.toMillis}")
    yield ()

object WebJarStore:

  val webJarsPathPrefix = "META-INF/resources/webjars"

  /**
   * File uploads queued per uploading GAV. Waiting is cheap (a fiber, no
   * bytes); `maxConcurrentFileUploads` is what limits actual work.
   */
  private val perGavParallelism = 64

  /**
   * File uploads (entry read + PUT) in flight across all GAVs. Sized so the
   * S3 client's connection pool keeps plenty of headroom for reads; at ~20 ms
   * per same-region PUT this is several thousand PUTs/s.
   */
  private val maxConcurrentFileUploads = 128

  /**
   * GAVs being downloaded/served from staging/uploaded at once. Mostly bounds
   * staging disk and concurrent Maven Central downloads.
   */
  private val defaultMaxConcurrentUploads = 64

  /** Bound for `absent`; real webjar versions number ~83k, junk paths are unbounded. */
  private val maxAbsent = 200_000

  private val corruptRetries = 2

  def isWebJarFile(name: String): Boolean =
    name.startsWith(webJarsPathPrefix) && !name.endsWith("/")

  def fileKey(gav: GroupArtifactVersion, entryName: String): StoreKey =
    StoreKey(s"files/$gav/${entryName.stripPrefix(s"$webJarsPathPrefix/")}")

  def manifestKey(gav: GroupArtifactVersion): StoreKey =
    StoreKey(s"manifests/$gav.json")

  private def contentType(name: String): Option[MediaType] =
    name.split('.').lastOption.flatMap(MediaType.forFileExtension)

  /** What the in-memory index keeps per stored GAV. */
  final case class StoredGav(meta: JarMeta, versionedLayout: Boolean):
    def source(gav: GroupArtifactVersion, objects: ObjectStore): WebJarSource =
      WebJarSource.Stored(gav, meta, versionedLayout, objects)

  private def versionedLayout(gav: GroupArtifactVersion, files: Seq[String]): Boolean =
    val prefix = s"$webJarsPathPrefix/${gav.artifactId}/${gav.version}/"
    files.exists(_.startsWith(prefix))

  extension (m: Manifest)
    def meta: JarMeta = JarMeta(m.lastModified, m.etag.flatMap(Header.ETag.parse(_).toOption))

  def readManifest(store: ObjectStore, gav: GroupArtifactVersion): Task[Option[Manifest]] =
    store.read(manifestKey(gav)).flatMap: maybeBytes =>
      ZIO.foreach(maybeBytes)(bytes => Body.fromChunk(bytes).asJson[Manifest])

  /** A fresh staging directory, deleted (with anything left in it) when the scope closes. */
  val stagingDir: ZIO[Scope, Nothing, File] =
    ZIO.acquireRelease(
      ZIO.attemptBlockingIO(java.nio.file.Files.createTempDirectory("webjar-staging").nn.toFile).orDie
    )(dir =>
      ZIO.attemptBlockingIO:
        Option(dir.listFiles()).foreach(_.foreach(f => f.nn.delete()))
        dir.delete()
      .ignoreLogged
    )

  def make(objects: ObjectStore, stagingDir: File, maxConcurrentUploads: Int, maxConcurrentFileUploads: Int = maxConcurrentFileUploads): UIO[WebJarStore] =
    for
      permits <- Semaphore.make(maxConcurrentFileUploads.toLong)
      stored  <- Ref.make(Map.empty[GroupArtifactVersion, StoredGav])
      absent  <- Ref.make(Set.empty[GroupArtifactVersion])
      slots   <- Ref.make(Map.empty[GroupArtifactVersion, Promise[FetchError, StagedJar]])
      staged  <- Ref.make(Map.empty[File, Long])
      busy    <- Ref.make(0L)
    yield WebJarStore(objects, stagingDir, maxConcurrentUploads, permits, stored, absent, slots, staged, busy)

  val layer: ZLayer[ObjectStore, Nothing, WebJarStore] =
    ZLayer.scoped:
      for
        objects <- ZIO.service[ObjectStore]
        dir     <- stagingDir
        store   <- make(objects, dir, defaultMaxConcurrentUploads)
      yield store
