import com.jamesward.zio_mavencentral.{GavCacheMiddleware, JarCache, MavenCentral}
import zio.*
import zio.http.*
import zio.http.codec.PathCodec
import zio.stream.ZStream

import java.io.FileNotFoundException
import java.lang.management.{BufferPoolMXBean, ManagementFactory, MemoryPoolMXBean}
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong
import scala.jdk.CollectionConverters.*

object App extends ZIOAppDefault:

  given CanEqual[Path, Path] = CanEqual.derived
  given CanEqual[Method, Method] = CanEqual.derived
  given CanEqual[Status, Status] = CanEqual.derived

  val webJarsPathPrefix: String = WebJarStore.webJarsPathPrefix

  type FilesEnv = Client & MavenCentral.MavenCentralRepo & WebJarStore

  // todo: use CORS Middleware from ZIO HTTP: https://ziohttp.com/reference/aop/middleware#access-control-allow-origin-cors-middleware
  // CORS middleware that adds Access-Control-Allow-Origin: * to all responses
  val corsMiddleware: HandlerAspect[Any, Unit] =
    HandlerAspect.interceptOutgoingHandler(Handler.fromFunction[Response] { response =>
      response.addHeader(Header.AccessControlAllowOrigin.All)
    })

  /**
   * Bracket pair around `GavCacheMiddleware` that adapts it to our
   * `/files/<groupId>/<artifactId>/<version>/<rest>` URL shape.
   *
   * `GavCacheMiddleware` parses GAV from the first three path segments,
   * so applying it directly to our routes wouldn't match. Instead of
   * reimplementing the middleware, we briefly rewrite the request path
   * to drop the `files` segment for the duration of the cache check,
   * then restore it before the request reaches the route table.
   *
   * The bracket is applied **only to the `/files/...` route subset**
   * (see `filesRoutes`/`otherRoutes` in `run`). Applying it around the
   * full route table would mis-prefix non-`/files` paths like
   * `/listfiles/...`, `/numfiles/...`, and `/robots.txt`, producing a
   * 308 redirect storm where each iteration grows the path.
   *
   * Composition (outermost → innermost):
   *
   *   stripFilesPrefix          // path: /files/g/a/v/rest → /g/a/v/rest
   *   GavCacheMiddleware.notModified  // 304 short-circuit on /g/a/v/rest
   *   GavCacheMiddleware.cacheHeaders // stable ETag + LM on /g/a/v/rest
   *   restoreFilesPrefix        // path: /g/a/v/rest → /files/g/a/v/rest
   *   filesRoutes
   *
   * The 304 path's `ETag` is derived from the stripped path (`/g/a/v/rest`).
   * Stability across dyno cycles is what matters for CDN revalidation;
   * the absolute ETag value just needs to be self-consistent. Clients
   * round-trip whatever value we sent, and `notModified` short-circuits
   * any conditional GET to an immutable GAV path regardless of validator
   * value, so this is sound.
   */
  private val FilesPrefix: Path = Path.root / "files"

  val stripFilesPrefix: HandlerAspect[Any, Unit] =
    HandlerAspect.interceptIncomingHandler:
      Handler.fromFunctionZIO[Request]: request =>
        // `unnest` drops the prefix Path's full size — i.e. both the leading
        // slash and the `files` segment. A naive `drop(1)` would drop only
        // the leading slash (zio-http treats slashes as positional segments
        // for `drop`), leaving `files` in place; combined with the restore
        // below, that produced `/files/files/...` paths and the redirect
        // loop we saw in production.
        ZIO.succeed((request.updatePath(_.unnest(FilesPrefix)), ()))

  val restoreFilesPrefix: HandlerAspect[Any, Unit] =
    HandlerAspect.interceptIncomingHandler:
      Handler.fromFunctionZIO[Request]: request =>
        ZIO.succeed((request.updatePath(p => FilesPrefix ++ p), ()))

  /**
   * Counter of HTTP responses currently streaming a webjar file (from the
   * object store or a staged local jar). Incremented when the response body
   * starts and decremented when its scope closes (success, error, or
   * interrupt). If it climbs without bound, requests are leaking streams.
   */
  private val inFlightStreams: AtomicLong = AtomicLong(0L)


  /**
   * JVM management beans, captured once at class init. `oldGenPool` and
   * `directBufferPool` are looked up by name because the canonical pool
   * names depend on the GC algorithm (G1 → "G1 Old Gen", Parallel → "PS
   * Old Gen", Serial → "Tenured Gen") and on JDK version.
   *
   * `unixOsBean` is `Some` on Linux/macOS (Heroku is Linux). Open file
   * descriptor count is the cheapest proxy for "how many `ZipFile`
   * instances does the cache currently hold open"; one fd per cached jar
   * is the model we want to validate.
   */
  private val memoryBean = ManagementFactory.getMemoryMXBean.nn

  private val oldGenPool: Option[MemoryPoolMXBean] =
    ManagementFactory.getMemoryPoolMXBeans.nn.asScala.toList
      .find: p =>
        val name = p.getName.nn
        name.contains("Old") || name.contains("Tenured")

  private val directBufferPool: Option[BufferPoolMXBean] =
    ManagementFactory.getPlatformMXBeans(classOf[BufferPoolMXBean]).nn.asScala.toList
      .find(_.getName.nn == "direct")

  private val unixOsBean: Option[com.sun.management.UnixOperatingSystemMXBean] =
    ManagementFactory.getOperatingSystemMXBean match
      case b: com.sun.management.UnixOperatingSystemMXBean => Some(b)
      case _                                               => None

  /**
   * Background fiber: emit a structured snapshot every 30s of heap/old-gen/
   * direct/fd counts alongside the staging jar cache, the object-store
   * population state (including busy rejections), and the in-flight stream count.
   * Every value is on one line so `grep memory_snapshot` plots cleanly.
   */
  val snapshotLogger: ZIO[WebJarStore, Nothing, Long] =
    ZIO.serviceWithZIO[WebJarStore]: store =>
      store.jars.size.zip(store.jars.totalBytes).zip(store.storedCount).zip(store.uploadingCount).zip(store.busyCount).flatMap: (staged, stagedBytes, storedGavs, uploadingGavs, busy) =>
        ZIO.succeed:
          val heap          = memoryBean.getHeapMemoryUsage.nn
          val (oldUsed, oldMax) = oldGenPool match
            case Some(p) =>
              val u = p.getUsage.nn
              (u.getUsed, u.getMax)
            case None => (-1L, -1L)
          val directUsed = directBufferPool.fold(0L)(_.getMemoryUsed)
          val openFds    = unixOsBean.fold(-1L)(_.getOpenFileDescriptorCount)
          val inFlight   = inFlightStreams.get()
          (heap.getUsed, heap.getMax, oldUsed, oldMax, directUsed, openFds, inFlight)
        .flatMap: (heapUsed, heapMax, oldUsed, oldMax, directUsed, openFds, inFlight) =>
          ZIO.logInfo(
            s"memory_snapshot " +
              s"heap_used_mb=${heapUsed / 1024 / 1024} " +
              s"heap_max_mb=${heapMax / 1024 / 1024} " +
              s"old_used_mb=${oldUsed / 1024 / 1024} " +
              s"old_max_mb=${oldMax / 1024 / 1024} " +
              s"direct_used_mb=${directUsed / 1024 / 1024} " +
              s"open_fds=$openFds " +
              s"staged_jars=$staged " +
              s"staged_mb=${stagedBytes / 1024 / 1024} " +
              s"stored_gavs=$storedGavs " +
              s"uploading_gavs=$uploadingGavs " +
              s"uploads_busy_total=$busy " +
              s"in_flight_streams=$inFlight"
          )
    .repeat(Schedule.fixed(30.seconds))

  case class JarDetails(etag: Option[Header.ETag], lastModified: Option[Header.LastModified], contents: ZStream[Any, Throwable, String])

  /**
   * Map the download path's typed errors into the Throwable channel the
   * route handlers use: `NotFoundError` → `FileNotFoundException` (404),
   * `UpstreamCorruptError` (Maven Central served bytes that aren't a valid
   * jar, e.g. `webvr-polyfill-dpdb/1.0.7.jar` is 16 KB of null bytes) →
   * [[WebJarCorruptException]] (422), [[UploadsBusy]] →
   * [[UploadsBusyException]] (503).
   */
  private def fetch(gav: MavenCentral.GroupArtifactVersion): ZIO[FilesEnv, java.io.IOException, WebJarSource] =
    ZIO.serviceWithZIO[WebJarStore](_.fetch(gav)).mapError:
      case _: MavenCentral.NotFoundError =>
        FileNotFoundException(s"WebJar not found: $gav")
      case e: JarCache.UpstreamCorruptError =>
        WebJarCorruptException(s"WebJar bytes are corrupt: ${e.gav} (size=${e.sizeBytes}B): ${e.reason}")
      case UploadsBusy(_) =>
        UploadsBusyException(s"WebJar $gav is not stored yet and all upload slots are busy")

  /** The source for a GAV, downloading it from Maven Central if it isn't stored or staged. */
  def webJarSource(gav: MavenCentral.GroupArtifactVersion): ZIO[FilesEnv, java.io.IOException, WebJarSource] =
    ZIO.serviceWithZIO[WebJarStore](_.known(gav)).someOrElseZIO(fetch(gav))

  final class WebJarCorruptException(message: String) extends java.io.IOException(message)
  final class UploadsBusyException(message: String) extends java.io.IOException(message)

  /** How long clients should wait before retrying a busy miss. */
  private val busyRetryAfter: Duration = 30.seconds

  /**
   * Map an error from a `/files`/`/listfiles`/`/numfiles` request flow
   * to an HTTP response. [[WebJarCorruptException]] becomes 422 (the
   * artifact exists but cannot be processed); [[UploadsBusyException]]
   * becomes an uncacheable 503 with `Retry-After`; every other failure
   * shape collapses to 404 (the artifact / file isn't here).
   */
  private def errorResponse(t: Throwable): Response =
    t match
      case _: WebJarCorruptException =>
        Response(
          Status.UnprocessableEntity,
          body = Body.fromString(t.getMessage),
        )
      case _: UploadsBusyException =>
        Response(
          Status.ServiceUnavailable,
          Headers(Header.Custom("Retry-After", busyRetryAfter.toSeconds.toString), Header.CacheControl.NoStore),
          Body.fromString(t.getMessage),
        )
      case _ =>
        Response.notFound(t.getMessage)

  def fetchFileList(gav: MavenCentral.GroupArtifactVersion): ZIO[FilesEnv, Throwable, JarDetails] =
    webJarSource(gav).flatMap: source =>
      source.fileNames.map: names =>
        JarDetails(
          source.meta.maybeEtag,
          source.meta.maybeLastModified.map(Header.LastModified(_)),
          ZStream.fromIterable(names),
        )

  /** Stream the bytes of a single jar entry; fails with `NoSuchFileException` if it's missing. */
  def readFileFromJar(gav: MavenCentral.GroupArtifactVersion, entryPath: String): ZStream[FilesEnv, Throwable, Byte] =
    ZStream.fromZIO(webJarSource(gav)).flatMap: source =>
      ZStream.fromZIO(source.hasFile(entryPath)).flatMap: exists =>
        if exists then source.stream(entryPath)
        else ZStream.fail(java.nio.file.NoSuchFileException(entryPath))

  case class FileDetails(etag: Option[Header.ETag], lastModified: Option[Header.LastModified], file: String)

  /**
   * Resolve a request path to a jar entry, tolerating the two layouts
   * webjars use in practice (with or without the version segment in
   * the resource path).
   */
  def findFile(gav: MavenCentral.GroupArtifactVersion, source: WebJarSource, file: Path): Task[FileDetails] =
    val withVersion    = s"$webJarsPathPrefix/${gav.artifactId}/${gav.version}/$file"
    val withoutVersion = s"$webJarsPathPrefix/${gav.artifactId}/$file"
    def details(entry: String) =
      FileDetails(source.meta.maybeEtag, source.meta.maybeLastModified.map(Header.LastModified(_)), entry)
    source.hasFile(withVersion).flatMap: hasV =>
      if hasV then ZIO.succeed(details(withVersion))
      else
        source.hasFile(withoutVersion).flatMap: hasNV =>
          if hasNV then ZIO.succeed(details(withoutVersion))
          else ZIO.fail(FileNotFoundException(s"$file not found in WebJar"))

  def serveFile(source: WebJarSource, fileDetails: FileDetails, request: Request): Response =
    val maybeFileExt = fileDetails.file.split('.').lastOption
    val maybeMimeType = maybeFileExt.flatMap(MediaType.forFileExtension)

    val commonHeaders = Headers(Header.CacheControl.Multiple(NonEmptyChunk(Header.CacheControl.Public, Header.CacheControl.MaxAge(31536000), Header.CacheControl.Immutable)))
      .addHeaders(Headers(maybeMimeType.map(Header.ContentType(_)).toSeq))
      .addHeaders(Headers(fileDetails.etag.toSeq))
      .addHeaders(Headers(fileDetails.lastModified.toSeq))

    val clientEtag = request.header(Header.IfNoneMatch)
    val etagMatches = (clientEtag, fileDetails.etag) match
      case (Some(ifNoneMatch), Some(serverEtag)) =>
        ifNoneMatch.renderedValue.contains(serverEtag.renderedValue) ||
          ifNoneMatch.renderedValue.contains("*")
      case _ => false

    val clientIfModifiedSince = request.header(Header.IfModifiedSince)
    val notModified = (clientIfModifiedSince, fileDetails.lastModified) match
      case (Some(ifModifiedSince), Some(serverLastModified)) =>
        val clientTime = ifModifiedSince.value
        !serverLastModified.value.isAfter(clientTime)
      case _ => false

    if etagMatches || notModified then
      Response(
        status = Status.NotModified,
        headers = commonHeaders,
      )
    else
      // `unwrapScoped` + `acquireRelease` keeps the `inFlightStreams` gauge
      // accurate across success, stream error, and client interrupt (H27):
      // the scope spans the whole body stream's lifetime.
      val entryStream: ZStream[Any, Throwable, Byte] =
        ZStream.unwrapScoped[Any]:
          ZIO.acquireRelease(
            ZIO.succeed { inFlightStreams.incrementAndGet(); () }
          )(_ => ZIO.succeed { inFlightStreams.decrementAndGet(); () })
            .as(source.stream(fileDetails.file))
      Response(
        status  = Status.Ok,
        headers = commonHeaders,
        body    = Body.fromStreamChunked(entryStream),
      )

  def fileHandler(gav: MavenCentral.GroupArtifactVersion, file: Path, request: Request): Handler[FilesEnv, Nothing, (MavenCentral.GroupArtifactVersion, Path, Request), Response] =
    Handler.fromZIO:
      if gav.groupId.toString.startsWith("org.webjars") then
        ZIO.serviceWithZIO[WebJarStore](_.known(gav)).flatMap: maybeSource =>
          val sourceLabel = maybeSource.fold("MISS"):
            case _: WebJarSource.Stored => "STORED"
            case _: WebJarSource.Local  => "LOCAL"
          ZIO.logInfo(s"webjar_source=$sourceLabel gav=$gav") *>
            maybeSource.fold(fetch(gav))(ZIO.succeed(_))
              .flatMap(source => findFile(gav, source, file).map(serveFile(source, _, request)))
              .catchAll(e => ZIO.succeed(errorResponse(e)))
      else
        val path = Path.root / "files" / "org.webjars" ++ request.url.path.drop(2)
        ZIO.succeed(Response.redirect(request.url.path(path), true))

  def listFilesHandler(gav: MavenCentral.GroupArtifactVersion, req: Request): Handler[FilesEnv, Nothing, (MavenCentral.GroupArtifactVersion, Request), Response] =
    Handler.fromZIO:
      fetchFileList(gav)
    .flatMap:
      jarDetails =>
        val jsonStream: ZStream[Any, Throwable, String] =
          ZStream.succeed("[") ++
            jarDetails.contents.map("\"" + _ + "\"").intersperse(",") ++
            ZStream.succeed("]")

        // todo cache headers
        Handler.fromStreamChunked(jsonStream)
          .map(_.contentType(MediaType.application.json))
    .catchAll:
      e => errorResponse(e).toHandler

  def numFilesHandler(gav: MavenCentral.GroupArtifactVersion, req: Request): Handler[FilesEnv, Nothing, (MavenCentral.GroupArtifactVersion, Request), Response] =
    // todo cache headers
    Handler.fromZIO:
      fetchFileList(gav)
        .flatMap(_.contents.runCount)
        .map(num => Response.text(num.toString))
    .catchAll:
      e => errorResponse(e).toHandler

  val fileOptionsHandler: Handler[Any, Nothing, Request, Response] =
    Handler.fromFunction[Request] { _ =>
      Response.ok.addHeader(Header.AccessControlAllowHeaders.All)
    }

  val corsPreflightHandler: Handler[Any, Nothing, Request, Response] =
    Handler.fromFunction[Request] { _ =>
      Response.ok
        .addHeader(Header.AccessControlAllowOrigin.All)
        .addHeader(Header.AccessControlAllowMethods(Method.GET))
    }

  val robotsTxt =
    """User-agent: *
      |Disallow: /files/
      |Disallow: /listfiles/
      |""".stripMargin

  // Use the GAV PathCodecs from zio-mavencentral 0.8.0 instead of locally
  // re-defining them.
  private val groupId    = MavenCentral.Codecs.groupId
  private val artifactId = MavenCentral.Codecs.artifactId
  private val version    = MavenCentral.Codecs.version

  val artifactVersionPathCodec: PathCodec[MavenCentral.GroupArtifactVersion] =
    val base = artifactId / version
    base.transform(av => MavenCentral.GroupArtifactVersion(MavenCentral.GroupId("org.webjars"), av._1, av._2))(av => (av.artifactId, av.version))

  val groupArtifactVersionPathCodec: PathCodec[MavenCentral.GroupArtifactVersion] =
    MavenCentral.Codecs.groupArtifactVersion

  def addGroupId(gav: MavenCentral.GroupArtifactVersion, req: Request): Handler[Any, Nothing, (MavenCentral.GroupArtifactVersion, Request), Response] =
    val op = req.path.take(2)
    val path = op ++ gav.toPath
    Response.redirect(req.url.path(path), true).toHandler

  /**
   * Routes whose paths start with `/files/...`. These are the ones that
   * benefit from `GavCacheMiddleware`'s GAV-shaped 304 short-circuit and
   * stable cache validators, and these are the ones the strip/restore
   * brackets are tuned for.
   */
  val filesRoutes: Routes[FilesEnv, Response] =
    Routes(
      Method.GET / "files" / groupArtifactVersionPathCodec / trailing -> Handler.fromFunctionHandler[(MavenCentral.GroupArtifactVersion, Path, Request)](fileHandler),
      Method.OPTIONS / "files" / trailing -> fileOptionsHandler,
      Method.GET / "files" / "robots.txt" -> Handler.text(robotsTxt),
    )

  /**
   * Everything else — `/listfiles`, `/numfiles`, top-level `/robots.txt`,
   * the catch-all CORS preflight. These intentionally do **not** go
   * through the strip/restore brackets: the brackets unconditionally
   * re-prepend `/files` on the way back in, which would mangle every
   * non-`/files` path.
   */
  val otherRoutes: Routes[FilesEnv, Response] =
    Routes(
      Method.GET / "listfiles" / artifactVersionPathCodec -> Handler.fromFunctionHandler[(MavenCentral.GroupArtifactVersion, Request)](addGroupId),
      Method.GET / "listfiles" / groupArtifactVersionPathCodec -> Handler.fromFunctionHandler[(MavenCentral.GroupArtifactVersion, Request)](listFilesHandler),
      Method.GET / "numfiles" / artifactVersionPathCodec -> Handler.fromFunctionHandler[(MavenCentral.GroupArtifactVersion, Request)](addGroupId),
      Method.GET / "numfiles" / groupArtifactVersionPathCodec -> Handler.fromFunctionHandler[(MavenCentral.GroupArtifactVersion, Request)](numFilesHandler),
      Method.GET / "robots.txt" -> Handler.text(robotsTxt),
      Method.OPTIONS / trailing -> corsPreflightHandler,
    )

  val routes: Routes[FilesEnv, Response] =
    filesRoutes ++ otherRoutes

  val serverConfig =
    ZLayer.fromZIO:
      for
        system <- ZIO.system
        maybePort <- system.env("PORT")
      yield
        maybePort.flatMap(_.toIntOption).fold(Server.Config.default)(Server.Config.default.port).copy(
          responseCompression = Some(Server.Config.ResponseCompressionConfig.default)
        )

  val serverLayer: ZLayer[Any, Throwable, Server] =
    serverConfig >>> Server.live

  /**
   * `filesRoutes` wrapped in the strip → cache → restore bracket. Kept
   * separate from `otherRoutes` so the bracket only applies to paths
   * that actually start with `/files/`.
   */
  val cachedFilesRoutes: Routes[FilesEnv, Response] =
    filesRoutes
      @@ restoreFilesPrefix
      @@ GavCacheMiddleware.cacheHeaders()
      @@ GavCacheMiddleware.notModified()
      @@ stripFilesPrefix

  /**
   * Request headers included as structured log annotations.
   *
   * jsDelivr replaces `User-Agent` on origin fetches with its own
   * `jsdelivr-origin/...` value, and does not publicly document a header that
   * always carries the end-user UA. Keep that origin-facing value for
   * diagnostics, but also capture common proxy conventions when an upstream
   * chooses to provide them. Likewise, log the standard `Referer` and `Origin`
   * headers plus their common forwarded/original variants. Missing headers
   * simply produce no annotation, so we never manufacture client metadata.
   */
  private def customHeaderType(name: String): Header.HeaderTypeBase =
    Header.Custom(name, "").headerType

  val loggedRequestHeaders: Set[Header.HeaderTypeBase] = Set(
    Header.UserAgent,
    customHeaderType("X-Forwarded-User-Agent"),
    customHeaderType("X-Original-User-Agent"),
    Header.Referer,
    Header.Origin,
    customHeaderType("X-Forwarded-Referer"),
    customHeaderType("X-Original-Referer"),
    Header.IfNoneMatch,
    Header.IfModifiedSince,
  )

  val requestLogging: HandlerAspect[Any, Unit] =
    HandlerAspect.requestLogging(loggedRequestHeaders = loggedRequestHeaders)

  /**
   * The full HTTP app as deployed: `/files`-prefixed routes go through
   * `GavCacheMiddleware` (with the strip/restore bracket); everything
   * else routes directly. CORS and request logging wrap the whole thing.
   *
   * Tests should exercise this rather than `routes` when they want to
   * cover middleware behavior — the bug that produced the prod 308 loop
   * was invisible to tests that called `routes.runZIO` directly.
   */
  val app: Routes[FilesEnv, Response] =
    (cachedFilesRoutes ++ otherRoutes)
      @@ corsMiddleware
      @@ requestLogging

  /** The server, with the object store as the only swappable piece (see `AppTest`). */
  def serve(objectStore: ZLayer[Any, Throwable, ObjectStore]): ZIO[Any, Throwable, Nothing] =
    (snapshotLogger.forkDaemon *> Server.serve(app)).provide(
      serverLayer,
      Client.default.update(_ @@ ZClientAspect.requestLogging()),
      // Google's GCS mirror is the default backend, with Maven Central
      // (repo1 + the Apache alias) as fallback. Rationale: under our
      // crawler-driven long-tail load, repo1.maven.org throttles the app
      // with 403/429s, which trips the per-mirror circuit breaker and
      // sidelines it for `resetMin`. GCS tolerates the request volume
      // better, so leading with it keeps the fast path open; repo1 is
      // still there to catch anything GCS is missing.
      MavenCentral.MavenCentralRepo.custom(
        mirrors = List(
          MavenCentral.gcsMirror,
          MavenCentral.primaryUri,
          MavenCentral.apacheAlias,
        )
      ),
      objectStore,
      WebJarStore.layer,
    )

  override val run = serve(S3ObjectStore.fromEnv)
