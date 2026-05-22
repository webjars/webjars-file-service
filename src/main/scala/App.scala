import com.jamesward.zio_mavencentral.{GavCacheMiddleware, JarCache, MavenCentral}
import zio.*
import zio.http.*
import zio.http.codec.PathCodec
import zio.stream.ZStream

import java.io.FileNotFoundException
import java.nio.file.Files

object App extends ZIOAppDefault:

  given CanEqual[Path, Path] = CanEqual.derived
  given CanEqual[Method, Method] = CanEqual.derived
  given CanEqual[Status, Status] = CanEqual.derived

  val webJarsPathPrefix = "META-INF/resources/webjars"

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
   * Background fiber: log `JarCache` size + on-disk bytes once a minute.
   * Useful for spotting unbounded growth on Heroku's ephemeral disk.
   * `JarCache` itself has no eviction, so the only natural reset is dyno
   * restart — these stats let us see how close to that limit we are.
   */
  val cacheStatsLogger: ZIO[JarCache, Nothing, Long] =
    ZIO.serviceWithZIO[JarCache]: cache =>
      cache.size.zip(cache.totalBytes).flatMap: (jars, bytes) =>
        ZIO.logInfo(s"cache_stats jars=$jars total_mb=${bytes / 1024 / 1024}")
    .repeat(Schedule.fixed(60.seconds))

  case class JarDetails(etag: Option[Header.ETag], lastModified: Option[Header.LastModified], contents: ZStream[Any, Throwable, String])

  /**
   * Cache of fully-downloaded webjars on disk plus open `ZipFile` handles.
   * Backed by `zio-mavencentral`'s [[JarCache]]: each entry owns one
   * immutable `.jar` file and one `ZipFile` handle. Reads use random
   * access into the jar — no extracted directory, no streaming-time
   * deletion races.
   */
  val jarCacheLayer: ZLayer[Client, Nothing, JarCache] =
    ZLayer.scoped:
      val cacheDir = Files.createTempDirectory("webjar-cache").nn.toFile
      JarCache.make(
        cacheDir,
        JarCache.httpDownloader(gav => MavenCentral.jarUri(gav.groupId, gav.artifactId, gav.version)),
        label = "webjar",
      )  /**
   * Look up the `JarHandle` for a webjar GAV. Maps the upstream
   * `NotFoundError` to a `FileNotFoundException` for parity with the
   * previous extracted-dir flow.
   */
  private def jarHandle(gav: MavenCentral.GroupArtifactVersion): ZIO[Client & JarCache & MavenCentral.MavenCentralRepo, FileNotFoundException, JarCache.JarHandle] =
    ZIO.serviceWithZIO[JarCache](_.get(gav)).orElseFail(FileNotFoundException(s"WebJar not found: $gav"))

  def fetchFileList(gav: MavenCentral.GroupArtifactVersion): ZIO[Client & JarCache & MavenCentral.MavenCentralRepo, Throwable, JarDetails] =
    jarHandle(gav).map: handle =>
      // Only entries inside `META-INF/resources/webjars/...` are user-visible
      // webjar content; jar metadata files (`META-INF/MANIFEST.MF`, `pom.xml`,
      // etc.) are filtered out.
      JarDetails(
        handle.meta.maybeEtag,
        handle.meta.maybeLastModified.map(Header.LastModified(_)),
        ZStream.fromIterableZIO(
          handle.filterEntryNames(name => name.startsWith(webJarsPathPrefix) && !name.endsWith("/"))
            .map(_.toSeq.sorted)
        ),
      )

  /**
   * Stream the bytes of a single jar entry. Fails with
   * `java.nio.file.NoSuchFileException` (preserving the previous
   * extracted-dir behavior) when the entry is missing — matched by
   * existing tests.
   */
  def readFileFromJar(gav: MavenCentral.GroupArtifactVersion, entryPath: String): ZStream[Client & JarCache & MavenCentral.MavenCentralRepo, Throwable, Byte] =
    ZStream.fromZIO(jarHandle(gav))
      .flatMap: handle =>
        ZStream.fromZIO(
          handle.readEntry(entryPath).orElseFail(new java.nio.file.NoSuchFileException(entryPath))
        ).flatMap(bytes => ZStream.fromChunk(Chunk.fromArray(bytes)))

  case class FileDetails(etag: Option[Header.ETag], lastModified: Option[Header.LastModified], file: String)

  /**
   * Resolve a request path to a jar entry, tolerating the two layouts
   * webjars use in practice (with or without the version segment in
   * the resource path).
   */
  def findFile(gav: MavenCentral.GroupArtifactVersion, file: Path): ZIO[Client & JarCache & MavenCentral.MavenCentralRepo, Throwable, FileDetails] =
    jarHandle(gav).flatMap: handle =>
      val withVersion    = s"$webJarsPathPrefix/${gav.artifactId}/${gav.version}/$file"
      val withoutVersion = s"$webJarsPathPrefix/${gav.artifactId}/$file"
      handle.hasEntry(withVersion).flatMap: hasV =>
        if hasV then ZIO.succeed(FileDetails(handle.meta.maybeEtag, handle.meta.maybeLastModified.map(Header.LastModified(_)), withVersion))
        else
          handle.hasEntry(withoutVersion).flatMap: hasNV =>
            if hasNV then ZIO.succeed(FileDetails(handle.meta.maybeEtag, handle.meta.maybeLastModified.map(Header.LastModified(_)), withoutVersion))
            else ZIO.fail(FileNotFoundException(s"$file not found in WebJar"))

  def serveFile(gav: MavenCentral.GroupArtifactVersion, fileDetails: FileDetails, request: Request): ZIO[Client & JarCache & MavenCentral.MavenCentralRepo, Throwable, Response] =
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
      ZIO.succeed:
        Response(
          status = Status.NotModified,
          headers = commonHeaders,
        )
    else
      jarHandle(gav).flatMap: handle =>
        handle.readEntry(fileDetails.file).orElseFail(new java.nio.file.NoSuchFileException(fileDetails.file))
      .map: bytes =>
        Response(
          status  = Status.Ok,
          headers = commonHeaders,
          body    = Body.fromArray(bytes),
        )

  def fileHandler(gav: MavenCentral.GroupArtifactVersion, file: Path, request: Request): Handler[Client & JarCache & MavenCentral.MavenCentralRepo, Nothing, (MavenCentral.GroupArtifactVersion, Path, Request), Response] =
    Handler.fromZIO:
      if gav.groupId.toString.startsWith("org.webjars") then
        ZIO.serviceWithZIO[JarCache](_.contains(gav)).flatMap: cached =>
          ZIO.logInfo(s"webjar_cache=${if cached then "HIT" else "MISS"} gav=$gav") *>
            findFile(gav, file).flatMap(fileDetails => serveFile(gav, fileDetails, request)).catchAll:
              e => ZIO.succeed(Response.notFound(e.getMessage))
      else
        val path = Path.root / "files" / "org.webjars" ++ request.url.path.drop(2)
        ZIO.succeed(Response.redirect(request.url.path(path), true))

  def listFilesHandler(gav: MavenCentral.GroupArtifactVersion, req: Request): Handler[Client & JarCache & MavenCentral.MavenCentralRepo, Nothing, (MavenCentral.GroupArtifactVersion, Request), Response] =
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
      e => Response.notFound(e.getMessage).toHandler

  def numFilesHandler(gav: MavenCentral.GroupArtifactVersion, req: Request): Handler[Client & JarCache & MavenCentral.MavenCentralRepo, Nothing, (MavenCentral.GroupArtifactVersion, Request), Response] =
    // todo cache headers
    Handler.fromZIO:
      fetchFileList(gav)
        .flatMap(_.contents.runCount)
        .map(num => Response.text(num.toString))
    .catchAll:
      e => Response.notFound(e.getMessage).toHandler

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
  val filesRoutes: Routes[Client & JarCache & MavenCentral.MavenCentralRepo, Response] =
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
  val otherRoutes: Routes[Client & JarCache & MavenCentral.MavenCentralRepo, Response] =
    Routes(
      Method.GET / "listfiles" / artifactVersionPathCodec -> Handler.fromFunctionHandler[(MavenCentral.GroupArtifactVersion, Request)](addGroupId),
      Method.GET / "listfiles" / groupArtifactVersionPathCodec -> Handler.fromFunctionHandler[(MavenCentral.GroupArtifactVersion, Request)](listFilesHandler),
      Method.GET / "numfiles" / artifactVersionPathCodec -> Handler.fromFunctionHandler[(MavenCentral.GroupArtifactVersion, Request)](addGroupId),
      Method.GET / "numfiles" / groupArtifactVersionPathCodec -> Handler.fromFunctionHandler[(MavenCentral.GroupArtifactVersion, Request)](numFilesHandler),
      Method.GET / "robots.txt" -> Handler.text(robotsTxt),
      Method.OPTIONS / trailing -> corsPreflightHandler,
    )

  val routes: Routes[Client & JarCache & MavenCentral.MavenCentralRepo, Response] =
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
  val cachedFilesRoutes: Routes[Client & JarCache & MavenCentral.MavenCentralRepo, Response] =
    filesRoutes
      @@ restoreFilesPrefix
      @@ GavCacheMiddleware.cacheHeaders()
      @@ GavCacheMiddleware.notModified()
      @@ stripFilesPrefix

  /**
   * The full HTTP app as deployed: `/files`-prefixed routes go through
   * `GavCacheMiddleware` (with the strip/restore bracket); everything
   * else routes directly. CORS and request logging wrap the whole thing.
   *
   * Tests should exercise this rather than `routes` when they want to
   * cover middleware behavior — the bug that produced the prod 308 loop
   * was invisible to tests that called `routes.runZIO` directly.
   */
  val app: Routes[Client & JarCache & MavenCentral.MavenCentralRepo, Response] =
    (cachedFilesRoutes ++ otherRoutes)
      @@ corsMiddleware
      @@ HandlerAspect.requestLogging(loggedRequestHeaders = Set(
        Header.UserAgent,
        Header.IfNoneMatch,
        Header.IfModifiedSince,
      ))

  override val run =
    (cacheStatsLogger.forkDaemon *> Server.serve(app)).provide(
      serverLayer,
      Client.default.update(_ @@ ZClientAspect.requestLogging()),
      MavenCentral.MavenCentralRepo.live,
      jarCacheLayer,
    )
