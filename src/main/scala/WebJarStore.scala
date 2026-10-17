import com.jamesward.zio_mavencentral.JarCache
import com.jamesward.zio_mavencentral.MavenCentral.{GroupArtifactVersion, JarMeta, MavenCentralRepo, NotFoundError}
import zio.*
import zio.http.{Body, Client, Header, MediaType}
import zio.schema.{Schema, derived}
import zio.stream.ZStream

import java.time.ZonedDateTime

/** Where a webjar's files are read from. */
enum WebJarSource:
  /** Fully extracted into the object store. */
  case Stored(gav: GroupArtifactVersion, jarMeta: JarMeta, store: ObjectStore)
  /** Downloaded to local disk; may still be uploading to the object store. */
  case Local(handle: JarCache.JarHandle)

  def meta: JarMeta =
    this match
      case Stored(_, jarMeta, _) => jarMeta
      case Local(handle)         => handle.meta

  def hasFile(entryName: String): Task[Boolean] =
    this match
      case Stored(gav, _, store) => store.size(WebJarStore.fileKey(gav, entryName)).map(_.isDefined)
      case Local(handle)         => handle.hasEntry(entryName)

  def stream(entryName: String): ZStream[Any, Throwable, Byte] =
    this match
      case Stored(gav, _, store) => store.stream(WebJarStore.fileKey(gav, entryName))
      case Local(handle)         => handle.streamEntry(entryName).mapError(_ => java.nio.file.NoSuchFileException(entryName))

  /** Sorted names of the user-visible webjar files. */
  def fileNames: Task[Seq[String]] =
    this match
      case Stored(gav, _, store) =>
        WebJarStore.readManifest(store, gav).someOrFail(IllegalStateException(s"manifest missing for stored $gav")).map(_.files)
      case Local(handle) =>
        handle.filterEntryNames(WebJarStore.isWebJarFile).map(_.toSeq.sorted)

/**
 * Per-GAV record written to the object store after every file of the
 * webjar has been uploaded. Its presence is what marks a GAV as stored, so
 * a partially-uploaded GAV (crash mid-upload) is simply re-populated.
 */
final case class Manifest(lastModified: Option[ZonedDateTime], etag: Option[String], files: List[String]) derives Schema

/**
 * A GAV that isn't stored yet was requested while every upload slot was in
 * use. The only overload condition: stored GAVs are always served.
 */
final case class UploadsBusy(gav: GroupArtifactVersion)

/**
 * Webjar file storage backed by an [[ObjectStore]].
 *
 * A request for a GAV that isn't stored yet takes an upload slot, downloads
 * the jar (via [[JarCache]], which single-flights downloads), serves from the
 * local copy, and uploads every webjar file to the object store in the
 * background. Requests for a GAV that is already uploading share its slot and
 * local copy. Once the manifest is written, all requests for that GAV are
 * served from the object store and the local jar ages out of the
 * [[JarCache]]; local disk is only a staging area.
 *
 * At most `maxConcurrentUploads` GAVs download and upload at once, which
 * bounds staging disk, open jars, and upstream/S3 concurrency. A miss beyond
 * that fails with [[UploadsBusy]] rather than queueing.
 *
 * `stored` remembers which GAVs are in the object store (with their cache
 * metadata) so each GAV costs at most one manifest read per process. It is
 * bounded by the size of the webjars catalog (~83k versions), so it needs no
 * eviction.
 */
final case class WebJarStore(
  objects: ObjectStore,
  jars: JarCache,
  maxConcurrentUploads: Int,
  stored: Ref[Map[GroupArtifactVersion, JarMeta]],
  uploading: Ref[Set[GroupArtifactVersion]],
  busyRejections: Ref[Long],
):
  import WebJarStore.*

  /** The source for a GAV if it's available without taking a new upload slot. */
  def known(gav: GroupArtifactVersion): ZIO[Client & MavenCentralRepo, Nothing, Option[WebJarSource]] =
    stored.get.map(_.get(gav)).flatMap:
      case Some(meta) => ZIO.some(WebJarSource.Stored(gav, meta, objects))
      case None =>
        uploading.get.map(_.contains(gav)).flatMap:
          // The jar is downloaded or downloading; `get` joins that download.
          case true  => jars.get(gav).fold(_ => None, h => Some(WebJarSource.Local(h)))
          case false => fromManifest(gav)

  /**
   * Take an upload slot for `gav` (or join the upload already holding one),
   * download the jar if needed, and populate the object store in the
   * background.
   */
  def fetch(gav: GroupArtifactVersion): ZIO[Client & MavenCentralRepo, NotFoundError | JarCache.UpstreamCorruptError | UploadsBusy, WebJarSource] =
    stored.get.map(_.get(gav)).flatMap:
      case Some(meta) => ZIO.succeed(WebJarSource.Stored(gav, meta, objects))
      case None =>
        uploading.modify: s =>
          if s.contains(gav) then (Slot.Joined, s)
          else if s.size >= maxConcurrentUploads then (Slot.Busy, s)
          else (Slot.Reserved, s + gav)
        .flatMap:
          case Slot.Busy =>
            busyRejections.update(_ + 1) *> ZIO.fail(UploadsBusy(gav))
          case Slot.Joined =>
            jars.get(gav).map(WebJarSource.Local(_))
          case Slot.Reserved =>
            val release = uploading.update(_ - gav)
            jars.get(gav)
              .tapError(_ => release)
              .tap: handle =>
                upload(gav, handle)
                  .catchAllCause(c => ZIO.logErrorCause(s"webjar upload failed gav=$gav", c))
                  .ensuring(release)
                  .forkDaemon
              .map(WebJarSource.Local(_))

  def storedCount: UIO[Int] = stored.get.map(_.size)
  def uploadingCount: UIO[Int] = uploading.get.map(_.size)
  def busyCount: UIO[Long] = busyRejections.get

  private def fromManifest(gav: GroupArtifactVersion): UIO[Option[WebJarSource]] =
    readManifest(objects, gav)
      .tapSome:
        case Some(manifest) => stored.update(_.updated(gav, manifest.meta))
      .map(_.map(m => WebJarSource.Stored(gav, m.meta, objects)))
      // An object-store outage degrades to the download path rather than failing requests.
      .catchAllCause(c => ZIO.logWarningCause(s"manifest read failed gav=$gav", c).as(None))

  private def upload(gav: GroupArtifactVersion, handle: JarCache.JarHandle): Task[Unit] =
    for
      names    <- handle.filterEntryNames(isWebJarFile).map(_.toList.sorted)
      duration <- ZIO.foreachParDiscard(names): name =>
                    handle.readEntry(name)
                      .mapError(e => java.nio.file.NoSuchFileException(e.path))
                      .flatMap(bytes => objects.write(fileKey(gav, name), Chunk.fromArray(bytes), contentType(name)))
                  .withParallelism(perGavParallelism)
                  .timed
                  .map(_._1)
      manifest  = Manifest(handle.meta.maybeLastModified, handle.meta.maybeEtag.map(_.renderedValue), names)
      json     <- Body.json(manifest).asChunk
      _        <- objects.write(manifestKey(gav), json, Some("application/json"))
      _        <- stored.update(_.updated(gav, manifest.meta))
      _        <- ZIO.logInfo(s"webjar_uploaded gav=$gav files=${names.size} duration_ms=${duration.toMillis}")
    yield ()

object WebJarStore:

  val webJarsPathPrefix = "META-INF/resources/webjars"

  /** PUTs in flight per uploading GAV; total is at most this × `maxConcurrentUploads`. */
  private val perGavParallelism = 16

  private enum Slot:
    case Reserved, Joined, Busy

  def isWebJarFile(name: String): Boolean =
    name.startsWith(webJarsPathPrefix) && !name.endsWith("/")

  def fileKey(gav: GroupArtifactVersion, entryName: String): StoreKey =
    StoreKey(s"files/$gav/${entryName.stripPrefix(s"$webJarsPathPrefix/")}")

  def manifestKey(gav: GroupArtifactVersion): StoreKey =
    StoreKey(s"manifests/$gav.json")

  private def contentType(name: String): Option[String] =
    name.split('.').lastOption.flatMap(MediaType.forFileExtension).map(_.fullType)

  extension (m: Manifest)
    def meta: JarMeta = JarMeta(m.lastModified, m.etag.flatMap(Header.ETag.parse(_).toOption))

  def readManifest(store: ObjectStore, gav: GroupArtifactVersion): Task[Option[Manifest]] =
    store.read(manifestKey(gav)).flatMap: maybeBytes =>
      ZIO.foreach(maybeBytes)(bytes => Body.fromChunk(bytes).asJson[Manifest])

  def make(objects: ObjectStore, jars: JarCache, maxConcurrentUploads: Int): UIO[WebJarStore] =
    for
      stored    <- Ref.make(Map.empty[GroupArtifactVersion, JarMeta])
      uploading <- Ref.make(Set.empty[GroupArtifactVersion])
      busy      <- Ref.make(0L)
    yield WebJarStore(objects, jars, maxConcurrentUploads, stored, uploading, busy)

  /**
   * The [[JarCache]] is only a staging area for jars being served and
   * uploaded: once a GAV is stored, nothing reads its local jar, so it idles
   * out. `coldIdleTtl` just needs to outlast a slow upload (every
   * `readEntry` resets the idle timer).
   */
  val stagingCache: ZIO[Scope, Nothing, JarCache] =
    ZIO.attemptBlockingIO(java.nio.file.Files.createTempDirectory("webjar-staging").nn.toFile).orDie.flatMap: dir =>
      JarCache.make(
        dir,
        JarCache.httpDownloader(gav => com.jamesward.zio_mavencentral.MavenCentral.jarUri(gav.groupId, gav.artifactId, gav.version)),
        label         = "webjar",
        warmIdleTtl   = 30.seconds,
        coldIdleTtl   = Some(10.minutes),
        sweepInterval = 15.seconds,
      )

  val layer: ZLayer[ObjectStore, Nothing, WebJarStore] =
    ZLayer.scoped:
      for
        objects <- ZIO.service[ObjectStore]
        jars    <- stagingCache
        // The previous design capped concurrent jar downloads at 8; uploads
        // now hold the slot for longer, so allow a few more.
        store   <- make(objects, jars, maxConcurrentUploads = 16)
      yield store
