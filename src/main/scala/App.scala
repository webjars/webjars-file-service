import com.jamesward.zio_mavencentral.MavenCentral
import zio.*
import zio.http.*
import zio.http.codec.PathCodec
import zio.stream.{ZPipeline, ZStream}

import java.io.{File, FileNotFoundException}
import java.nio.file.{Files, StandardOpenOption}
import scala.jdk.CollectionConverters.*

object App extends ZIOAppDefault:

  given CanEqual[Path, Path] = CanEqual.derived
  given CanEqual[Method, Method] = CanEqual.derived
  given CanEqual[Status, Status] = CanEqual.derived

  val baseJarUrl = zio.http.URL.decode("https://repo1.maven.org/maven2/").toOption.get
  val webJarsPathPrefix = "META-INF/resources/webjars"
  type TmpDir = File

  // todo: use CORS Middleware from ZIO HTTP: https://ziohttp.com/reference/aop/middleware#access-control-allow-origin-cors-middleware
  // CORS middleware that adds Access-Control-Allow-Origin: * to all responses
  val corsMiddleware: HandlerAspect[Any, Unit] =
    HandlerAspect.interceptOutgoingHandler(Handler.fromFunction[Response] { response =>
      response.addHeader(Header.AccessControlAllowOrigin.All)
    })

  def gavPath(gav: MavenCentral.GroupArtifactVersion): Path =
    MavenCentral.artifactPath(gav.groupId, Some(MavenCentral.ArtifactAndVersion(gav.artifactId, Some(gav.version))))

  def webJarUrl(gav: MavenCentral.GroupArtifactVersion): URL =
    baseJarUrl.addPath(gavPath(gav)) / (gav.artifactId.toString + "-" + gav.version.toString + ".jar")

  case class JarDetails(etag: Option[Header.ETag], lastModified: Option[Header.LastModified], contents: ZStream[Any, Throwable, String])

  type WebJarTmpDir = File

  extension (webJarTmpDir: WebJarTmpDir)
    def etagFile = File(webJarTmpDir, "etag")
    def lastmodifiedFile = File(webJarTmpDir, "lastmodified")
    def filelist = File(webJarTmpDir, "filelist")

  def gavTmpDir(gav: MavenCentral.GroupArtifactVersion): ZIO[TmpDir, Nothing, WebJarTmpDir] = ZIO.serviceWith[TmpDir](tmpDir => File(tmpDir, gav.toString))

  def saveFile(gav:MavenCentral.GroupArtifactVersion): ZIO[Client & TmpDir & Scope, Throwable, File] =
    gavTmpDir(gav).filterOrElseWith(_.exists()):
      webJarDir =>
        val url = webJarUrl(gav)
        MavenCentral.downloadAndExtractZip(url, webJarDir)
          .flatMap:
            info =>
              // todo: to stream
              ZIO.attemptBlockingIO:
                info.maybeEtag.foreach:
                  etag =>
                    Files.writeString(webJarDir.etagFile.toPath, etag.renderedValue)

                info.maybeLastModified.foreach:
                  lastModified =>
                    Files.writeString(webJarDir.lastmodifiedFile.toPath, Header.LastModified.render(Header.LastModified(lastModified)))

                val webJarFiles = info.value.filter(_.startsWith(webJarsPathPrefix))

                Files.write(webJarDir.filelist.toPath, webJarFiles.asJava, StandardOpenOption.CREATE)

                webJarDir
          .mapError(e => FileNotFoundException(s"WebJar not found: $gav"))

  def fetchFileList(gav: MavenCentral.GroupArtifactVersion): ZIO[Client & TmpDir & Scope, Throwable, JarDetails] =
    saveFile(gav).flatMap:
      webJarDir =>
        // todo: to zstream
        /*
        //        webJarDir <- gavTmpDir(gav)
        //        maybeEtag <- ZStream.fromPath(webJarDir.etagFile.toPath).via(ZPipeline.utf8Decode).collectZIO(s => ZIO.fromEither(Header.ETag.parse(s)))
        //        maybeLastModified <- ZStream.fromPath(webJarDir.lastmodifiedFile.toPath).via(ZPipeline.utf8Decode).collectZIO(s => ZIO.fromEither(Header.LastModified.parse(s)))
         */

        ZIO.attemptBlockingIO:
          val maybeEtag = Option.when(webJarDir.etagFile.exists())(Files.readString(webJarDir.etagFile.toPath)).flatMap(Header.ETag.parse(_).toOption)
          val maybeLastModified = Option.when(webJarDir.lastmodifiedFile.exists())(Files.readString(webJarDir.lastmodifiedFile.toPath)).flatMap(Header.LastModified.parse(_).toOption)

          JarDetails(
            maybeEtag,
            maybeLastModified,
            ZStream.fromPath(File(webJarDir, "filelist").toPath).via(ZPipeline.utf8Decode >>> ZPipeline.splitLines)
          )

  def readFileFromJar(gav: MavenCentral.GroupArtifactVersion, entryPath: String): ZStream[Client & TmpDir & Scope, Throwable, Byte] =
    ZStream.fromZIO:
        saveFile(gav)
    .flatMap:
      webJarDir =>
        ZStream.fromPath(File(webJarDir, entryPath).toPath)

  case class FileDetails(etag: Option[Header.ETag], lastModified: Option[Header.LastModified], file: String)

  def findFile(gav: MavenCentral.GroupArtifactVersion, file: Path): ZIO[Client & TmpDir & Scope, Throwable, FileDetails] =
    for
      webJarDetails <- fetchFileList(gav)
      fileInJar <- webJarDetails.contents.find:
        f =>
          f == s"$webJarsPathPrefix/${gav.artifactId}/${gav.version}/$file" ||
            f == s"$webJarsPathPrefix/${gav.artifactId}/$file" // some webjars don't include version in path
      .runHead.someOrFail(FileNotFoundException(s"$file not found in WebJar"))
    yield
      FileDetails(webJarDetails.etag, webJarDetails.lastModified, fileInJar)

  // todo: maybe use Handler.fromFileZIO instead? We'd just need to make sure the file's last-modified is set correctly.
  def serveFile(gav: MavenCentral.GroupArtifactVersion, fileDetails: FileDetails, request: Request): ZIO[Client & TmpDir & Scope, Throwable, Response] =
    val maybeFileExt = fileDetails.file.split('.').lastOption
    val maybeMimeType = maybeFileExt.flatMap(MediaType.forFileExtension)

    // todo: maybe better way
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
          headers = commonHeaders
        )
    else
      val fileStream = readFileFromJar(gav, fileDetails.file)

      Body.fromStreamChunkedEnv(fileStream).map:
        body =>
          Response(
            status = Status.Ok,
            headers = commonHeaders,
            body = body
          )

  def fileHandler(gav: MavenCentral.GroupArtifactVersion, file: Path, request: Request): Handler[Client & TmpDir, Nothing, (MavenCentral.GroupArtifactVersion, Path, Request), Response] =
    Handler.fromZIO:
      if gav.groupId.toString.startsWith("org.webjars") then
        ZIO.scoped:
          findFile(gav, file).flatMap(fileDetails => serveFile(gav, fileDetails, request)).catchAll:
            e => ZIO.succeed(Response.notFound(e.getMessage))
      else
        val path = Path.root / "files" / "org.webjars" ++ request.url.path.drop(2)
        ZIO.succeed(Response.redirect(request.url.path(path), true))

  def listFilesHandler(gav: MavenCentral.GroupArtifactVersion, req: Request): Handler[Client & TmpDir, Nothing, (MavenCentral.GroupArtifactVersion, Request), Response] =
    Handler.fromZIO:
      ZIO.scoped:
        fetchFileList(gav)
    .flatMap:
      jarDetails =>
        val jsonStream: ZStream[Client, Throwable, String] =
          ZStream.succeed("[") ++
            jarDetails.contents.map("\"" + _ + "\"").intersperse(",") ++
            ZStream.succeed("]")

        // todo cache headers
        Handler.fromStreamChunked(jsonStream)
          .map(_.contentType(MediaType.application.json))
    .catchAll:
      e => Response.notFound(e.getMessage).toHandler

  def numFilesHandler(gav: MavenCentral.GroupArtifactVersion, req: Request): Handler[Client & TmpDir, Nothing, (MavenCentral.GroupArtifactVersion, Request), Response] =
    // todo cache headers
    Handler.fromZIO:
      ZIO.scoped:
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

  val groupIdPathCodec: PathCodec[MavenCentral.GroupId] =
    string("groupId").transform(MavenCentral.GroupId(_))(_.toString)

  val artifactIdPathCodec: PathCodec[MavenCentral.ArtifactId] =
    string("artifactId").transform(MavenCentral.ArtifactId(_))(_.toString)

  val versionPathCodec: PathCodec[MavenCentral.Version] =
    string("version").transform(MavenCentral.Version(_))(_.toString)

  val artifactVersionPathCodec: PathCodec[MavenCentral.GroupArtifactVersion] =
    val base = artifactIdPathCodec / versionPathCodec
    base.transform(av => MavenCentral.GroupArtifactVersion(MavenCentral.GroupId("org.webjars"), av._1, av._2))(av => (av.artifactId, av.version))

  val groupArtifactVersionPathCodec: PathCodec[MavenCentral.GroupArtifactVersion] =
    val base = groupIdPathCodec / artifactIdPathCodec / versionPathCodec
    base.transform(gav => MavenCentral.GroupArtifactVersion(gav._1, gav._2, gav._3))(gav => (gav.groupId, gav.artifactId, gav.version))

  def addGroupId(gav: MavenCentral.GroupArtifactVersion, req: Request): Handler[Any, Nothing, (MavenCentral.GroupArtifactVersion, Request), Response] =
    val op = req.path.take(2)
    val path = op ++ gav.toPath
    Response.redirect(req.url.path(path), true).toHandler

  val routes: Routes[Client & TmpDir, Response] =
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

  val tmpDirLayer: ZLayer[Any, Nothing, TmpDir] =
    ZLayer.succeed(Files.createTempDirectory("webjars").toFile)

  override val run =
    Server.serve(routes @@ corsMiddleware @@ HandlerAspect.requestLogging(loggedRequestHeaders = Set(Header.UserAgent))).provide(
      serverLayer,
      Client.default.update(_ @@ ZClientAspect.requestLogging()),
      tmpDirLayer
    )
