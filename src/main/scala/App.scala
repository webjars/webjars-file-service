import com.jamesward.zio_mavencentral.MavenCentral
import zio.*
import zio.compress.ZipUnarchiver
import zio.http.*
import zio.http.Header.{AccessControlAllowHeaders, AccessControlAllowMethods, AccessControlAllowOrigin}
import zio.http.codec.PathCodec
import zio.stream.ZStream

import java.io.{FileNotFoundException, IOException}
import java.net.URI

object App extends ZIOAppDefault:

  given CanEqual[Path, Path] = CanEqual.derived
  given CanEqual[Method, Method] = CanEqual.derived
  given CanEqual[Status, Status] = CanEqual.derived

  val baseJarUrl = zio.http.URL.decode("https://repo1.maven.org/maven2/").toOption.get
  val webJarsPathPrefix = "META-INF/resources/webjars"

  // CORS middleware that adds Access-Control-Allow-Origin: * to all responses
  val corsMiddleware: HandlerAspect[Any, Unit] =
    HandlerAspect.interceptOutgoingHandler(Handler.fromFunction[Response] { response =>
      response.addHeader(AccessControlAllowOrigin.All)
    })

  def gavPath(gav: MavenCentral.GroupArtifactVersion): Path =
    MavenCentral.artifactPath(gav.groupId, Some(MavenCentral.ArtifactAndVersion(gav.artifactId, Some(gav.version))))

  def webJarUrl(gav: MavenCentral.GroupArtifactVersion): URL =
    baseJarUrl.addPath(gavPath(gav)) / (gav.artifactId.toString + "-" + gav.version.toString + ".jar")

  case class JarDetails(etag: Option[Header.ETag], lastModified: Option[Header.LastModified], contents: ZStream[Any, Throwable, String])

  def fetchFileList(gav: MavenCentral.GroupArtifactVersion): ZIO[Client, Throwable, JarDetails] =
    val url = webJarUrl(gav)
    for
      resp <- Client.batched(Request.get(url)).filterOrElseWith(_.status.isSuccess)(resp => ZIO.fail(FileNotFoundException(s"JAR not found: $url (status: ${resp.status})")))
    yield
      JarDetails(
        resp.header(Header.ETag),
        resp.header(Header.LastModified),
        resp.body.asStream.via(ZipUnarchiver.unarchive).map(_._1).filterNot(_.isDirectory).map(_.name).filter(_.startsWith(webJarsPathPrefix))
      )

  def readFileFromJar(jarFile: URL, entryPath: String): ZStream[Any, IOException, Byte] =
    val jarUrl = URI.create(s"jar:$jarFile!/$entryPath").toURL
    ZStream.fromInputStreamZIO(ZIO.attemptBlockingIO(jarUrl.openStream()))

  case class FileDetails(etag: Option[Header.ETag], lastModified: Option[Header.LastModified], file: String)

  def findFile(gav: MavenCentral.GroupArtifactVersion, file: Path): ZIO[Client & Scope, Throwable, FileDetails] =
    val maybeFile =
      for
        jarDetails <- fetchFileList(gav)
        fileList <- jarDetails.contents.runCollect
      yield
        fileList.find:
          fileInJar =>
            fileInJar == s"$webJarsPathPrefix/${gav.artifactId}/${gav.version}/$file" ||
              fileInJar == s"$webJarsPathPrefix/${gav.artifactId}/$file" // some webjars don't include version in path
        .map:
          fileInJar => FileDetails(jarDetails.etag, jarDetails.lastModified, fileInJar)

    maybeFile.someOrFail(FileNotFoundException(s"$file not found in WebJar"))

  def serveFile(gav: MavenCentral.GroupArtifactVersion, fileDetails: FileDetails, request: Request): Response =
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
      Response(
        status = Status.NotModified,
        headers = commonHeaders
      )
    else
      val fileStream = readFileFromJar(webJarUrl(gav), fileDetails.file)

      Response(
        status = Status.Ok,
        headers = commonHeaders,
        body = Body.fromStreamChunked(fileStream)
      )

  def fileHandler(gav: MavenCentral.GroupArtifactVersion, file: Path, request: Request): Handler[Client, Nothing, (MavenCentral.GroupArtifactVersion, Path, Request), Response] =
    Handler.fromZIO:
      ZIO.scoped:
        if gav.groupId.toString.startsWith("org.webjars") then
          findFile(gav, file).map(fileDetails => serveFile(gav, fileDetails, request)).catchAll:
            e => ZIO.succeed(Response.notFound(e.getMessage))

        else
          val path = Path.root / "files" / "org.webjars" ++ request.url.path.drop(2)
          ZIO.succeed(Response.redirect(request.url.path(path), true))

  def listFilesHandler(gav: MavenCentral.GroupArtifactVersion, req: Request): Handler[Client, Nothing, (MavenCentral.GroupArtifactVersion, Request), Response] =
    Handler.fromZIO(fetchFileList(gav))
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

  def numFilesHandler(gav: MavenCentral.GroupArtifactVersion, req: Request): Handler[Client, Nothing, (MavenCentral.GroupArtifactVersion, Request), Response] =
    // todo cache headers
    Handler.fromZIO(fetchFileList(gav).flatMap(_.contents.runCount)).map(_.toString).map(Response.text)
      .catchAll:
        e => Response.notFound(e.getMessage).toHandler

  val fileOptionsHandler: Handler[Any, Nothing, Request, Response] =
    Handler.fromFunction[Request] { _ =>
      Response.ok.addHeader(AccessControlAllowHeaders.All)
    }

  val corsPreflightHandler: Handler[Any, Nothing, Request, Response] =
    Handler.fromFunction[Request] { _ =>
      Response.ok
        .addHeader(AccessControlAllowOrigin.All)
        .addHeader(AccessControlAllowMethods(Method.GET))
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

  val routes: Routes[Client, Response] =
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

  val serverConfig: Server.Config = Server.Config.default.port(9000).copy(
    responseCompression = Some(Server.Config.ResponseCompressionConfig.default)
  )

  val serverLayer: ZLayer[Any, Throwable, Server] =
    ZLayer.succeed(serverConfig) >>> Server.live

  override val run =
    Server.serve(routes @@ corsMiddleware).provide(
      serverLayer,
      Client.default,
    )
