import com.jamesward.zio_mavencentral.{JarCache, MavenCentral}
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
      )

  /**
   * Look up the `JarHandle` for a webjar GAV. Maps the upstream
   * `NotFoundError` to a `FileNotFoundException` for parity with the
   * previous extracted-dir flow.
   */
  private def jarHandle(gav: MavenCentral.GroupArtifactVersion): ZIO[Client & JarCache, FileNotFoundException, JarCache.JarHandle] =
    ZIO.serviceWithZIO[JarCache](_.get(gav)).orElseFail(FileNotFoundException(s"WebJar not found: $gav"))

  def fetchFileList(gav: MavenCentral.GroupArtifactVersion): ZIO[Client & JarCache, Throwable, JarDetails] =
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
  def readFileFromJar(gav: MavenCentral.GroupArtifactVersion, entryPath: String): ZStream[Client & JarCache, Throwable, Byte] =
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
  def findFile(gav: MavenCentral.GroupArtifactVersion, file: Path): ZIO[Client & JarCache, Throwable, FileDetails] =
    jarHandle(gav).flatMap: handle =>
      val withVersion    = s"$webJarsPathPrefix/${gav.artifactId}/${gav.version}/$file"
      val withoutVersion = s"$webJarsPathPrefix/${gav.artifactId}/$file"
      handle.hasEntry(withVersion).flatMap: hasV =>
        if hasV then ZIO.succeed(FileDetails(handle.meta.maybeEtag, handle.meta.maybeLastModified.map(Header.LastModified(_)), withVersion))
        else
          handle.hasEntry(withoutVersion).flatMap: hasNV =>
            if hasNV then ZIO.succeed(FileDetails(handle.meta.maybeEtag, handle.meta.maybeLastModified.map(Header.LastModified(_)), withoutVersion))
            else ZIO.fail(FileNotFoundException(s"$file not found in WebJar"))

  def serveFile(gav: MavenCentral.GroupArtifactVersion, fileDetails: FileDetails, request: Request): ZIO[Client & JarCache, Throwable, Response] =
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

  def fileHandler(gav: MavenCentral.GroupArtifactVersion, file: Path, request: Request): Handler[Client & JarCache, Nothing, (MavenCentral.GroupArtifactVersion, Path, Request), Response] =
    Handler.fromZIO:
      if gav.groupId.toString.startsWith("org.webjars") then
        findFile(gav, file).flatMap(fileDetails => serveFile(gav, fileDetails, request)).catchAll:
          e => ZIO.succeed(Response.notFound(e.getMessage))
      else
        val path = Path.root / "files" / "org.webjars" ++ request.url.path.drop(2)
        ZIO.succeed(Response.redirect(request.url.path(path), true))

  def listFilesHandler(gav: MavenCentral.GroupArtifactVersion, req: Request): Handler[Client & JarCache, Nothing, (MavenCentral.GroupArtifactVersion, Request), Response] =
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

  def numFilesHandler(gav: MavenCentral.GroupArtifactVersion, req: Request): Handler[Client & JarCache, Nothing, (MavenCentral.GroupArtifactVersion, Request), Response] =
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

  val routes: Routes[Client & JarCache, Response] =
    Routes(
      Method.GET / "files" / groupArtifactVersionPathCodec / trailing -> Handler.fromFunctionHandler[(MavenCentral.GroupArtifactVersion, Path, Request)](fileHandler),
      Method.OPTIONS / "files" / trailing -> fileOptionsHandler,
      Method.GET / "listfiles" / artifactVersionPathCodec -> Handler.fromFunctionHandler[(MavenCentral.GroupArtifactVersion, Request)](addGroupId),
      Method.GET / "listfiles" / groupArtifactVersionPathCodec -> Handler.fromFunctionHandler[(MavenCentral.GroupArtifactVersion, Request)](listFilesHandler),
      Method.GET / "numfiles" / artifactVersionPathCodec -> Handler.fromFunctionHandler[(MavenCentral.GroupArtifactVersion, Request)](addGroupId),
      Method.GET / "numfiles" / groupArtifactVersionPathCodec -> Handler.fromFunctionHandler[(MavenCentral.GroupArtifactVersion, Request)](numFilesHandler),
      Method.GET / "robots.txt" -> Handler.text(robotsTxt),
      Method.GET / "files" / "robots.txt" -> Handler.text(robotsTxt),
      Method.OPTIONS / trailing -> corsPreflightHandler,
    )

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

  override val run =
    Server.serve(routes @@ corsMiddleware @@ HandlerAspect.requestLogging(loggedRequestHeaders = Set(Header.UserAgent))).provide(
      serverLayer,
      Client.default.update(_ @@ ZClientAspect.requestLogging()),
      jarCacheLayer,
    )
