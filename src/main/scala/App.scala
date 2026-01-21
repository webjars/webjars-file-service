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

  def fetchFileList(gav: MavenCentral.GroupArtifactVersion): ZIO[Client, Throwable, ZStream[Any, Throwable, String]] =
    val url = webJarUrl(gav)
    for
      resp <- Client.batched(Request.get(url)).filterOrElseWith(_.status.isSuccess)(resp => ZIO.fail(FileNotFoundException(s"JAR not found: $url (status: ${resp.status})")))
    yield resp.body.asStream.via(ZipUnarchiver.unarchive).map(_._1).filterNot(_.isDirectory).map(_.name).filter(_.startsWith(webJarsPathPrefix))

  def readFileFromJar(jarFile: URL, entryPath: String): ZStream[Any, IOException, Byte] =
    val jarUrl = URI.create(s"jar:$jarFile!/$entryPath").toURL
    ZStream.fromInputStreamZIO(ZIO.attemptBlockingIO(jarUrl.openStream()))

//  def getMimeType(fileName: String): MediaType =
//    val ext = fileName.lastIndexOf('.') match
//      case -1 => ""
//      case i => fileName.substring(i + 1).toLowerCase
//    ext match
//      case "js" => MediaType.application.javascript
//      case "mjs" => MediaType.application.javascript
//      case "css" => MediaType.text.css
//      case "html" | "htm" => MediaType.text.html
//      case "json" => MediaType.application.json
//      case "map" => MediaType.application.json
//      case "xml" => MediaType.application.xml
//      case "svg" => MediaType.image.`svg+xml`
//      case "png" => MediaType.image.png
//      case "jpg" | "jpeg" => MediaType.image.jpeg
//      case "gif" => MediaType.image.gif
//      case "ico" => MediaType.image.`x-icon`
//      case "woff" => MediaType.application.`font-woff`
//      case "woff2" => MediaType("font", "woff2")
//      case "ttf" => MediaType("font", "ttf")
//      case "eot" => MediaType.application.`vnd.ms-fontobject`
//      case "otf" => MediaType("font", "otf")
//      case "txt" => MediaType.text.plain
//      case "md" => MediaType.text.plain
//      case "ts" => MediaType.text.plain.copy(subType = "typescript")
//      case _ => MediaType.application.`octet-stream`

  // File handler with conditional request support (ETag/If-None-Match, Last-Modified/If-Modified-Since)
  def fileHandler(groupId: String, artifactId: String, version: String, file: String, request: Request): ZIO[Client & Scope, Response, Response] =
    ???
    /*
    val result = for
//      jarFile <- downloadJar(groupId, artifactId, version)
      fileList = fetchFileList(groupId, artifactId, version)


      pathPrefix = s"$webJarsPathPrefix/$artifactId"

      // Try to find the file with version in path first
      maybeFileWithVersion = fileList.find(_ == s"$pathPrefix/$version/$file")

      // Fall back to path without version (for webjars that don't include version in path)
      maybeFile = maybeFileWithVersion.orElse {
        fileList.find { webJarFile =>
          val pathWithoutVersion = webJarFile.stripPrefix(pathPrefix + "/")
          // Check if it matches directly (version might be first segment)
          pathWithoutVersion == file || pathWithoutVersion.dropWhile(_ != '/').drop(1) == file
        }
      }

      entryPath <- ZIO.fromOption(maybeFile)
        .mapError(_ => new FileNotFoundException(s"$file not found in WebJar"))

      url <- webJarUrl(groupId, artifactId, version).debug

      content <- readFileFromJar(url, entryPath)

      // Get JAR file last modified time for Last-Modified header
      lastModified <- ZIO.attemptBlocking(java.time.Instant.ofEpochMilli(jarFile.lastModified()))
    yield
      val mimeType = getMimeType(file)
      val etag = java.security.MessageDigest.getInstance("SHA-1")
        .digest(content)
        .map("%02x".format(_))
        .mkString

      // Check If-None-Match header for ETag validation
      val clientEtag = request.headers.get(Header.IfNoneMatch)
      val etagMatches = clientEtag.exists { ifNoneMatch =>
        ifNoneMatch.renderedValue.contains(etag) || ifNoneMatch.renderedValue.contains("*")
      }

      // Check If-Modified-Since header
      val clientIfModifiedSince = request.headers.get(Header.IfModifiedSince)
      val notModifiedSince = clientIfModifiedSince.exists { ifModifiedSince =>
        // Parse the date and compare - if file hasn't been modified since, return true
        val clientTime = ifModifiedSince.value
        !lastModified.isAfter(clientTime.toInstant)
      }

      // Common headers for both 200 and 304 responses
      val commonHeaders = Headers(
        ContentType(mimeType),
        Header.Custom("Cache-Control", "public, max-age=31536000, immutable"),
        Header.ETag.Strong(etag),
        Header.LastModified(java.time.ZonedDateTime.ofInstant(lastModified, java.time.ZoneOffset.UTC))
      )

      // Return 304 Not Modified if ETag matches or not modified since
      if etagMatches || notModifiedSince then
        Response(
          status = Status.NotModified,
          headers = commonHeaders
        )
      else
        Response(
          status = Status.Ok,
          headers = commonHeaders,
          body = Body.fromArray(content)
        )

    result.catchAll {
      case _: FileNotFoundException =>
        ZIO.fail(Response.notFound(s"WebJar Not Found $groupId : $artifactId : $version"))
      case e: Throwable =>
        ZIO.fail(Response.internalServerError(s"Could not find WebJar ($groupId : $artifactId : $version)\n${e.getMessage}"))
    }

     */

  // List files handler
  def listFilesHandler(gav: MavenCentral.GroupArtifactVersion, req: Request): Handler[Client, Nothing, (MavenCentral.GroupArtifactVersion, Request), Response] =
    Handler.fromZIO(fetchFileList(gav))
      .flatMap:
        stream =>
          val jsonStream: ZStream[Client, Throwable, String] =
            ZStream.succeed("[") ++
              stream.map("\"" + _ + "\"").intersperse(",") ++
              ZStream.succeed("]")

          Handler.fromStreamChunked(jsonStream)
            .map(_.contentType(MediaType.application.json))
      .catchAll:
        e => Response.notFound(e.getMessage).toHandler

  def numFilesHandler(gav: MavenCentral.GroupArtifactVersion, req: Request): Handler[Client, Nothing, (MavenCentral.GroupArtifactVersion, Request), Response] =
    Handler.fromZIO(fetchFileList(gav).flatMap(_.runCount)).map(_.toString).map(Response.text)
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

  // todo: has to start with org.webjars
  val groupIdPathCodec: PathCodec[MavenCentral.GroupId] =
    string("groupId").transform(MavenCentral.GroupId(_))(_.toString)

  val artifactIdPathCodec: PathCodec[MavenCentral.ArtifactId] =
    string("artifactId").transform(MavenCentral.ArtifactId(_))(_.toString)

  val versionPathCodec: PathCodec[MavenCentral.Version] =
    string("version").transform(MavenCentral.Version(_))(_.toString)

  val artifactVersionPathCodec: PathCodec[MavenCentral.GroupArtifactVersion] =
    val base = PathCodec.empty / artifactIdPathCodec / versionPathCodec
    base.transform(av => MavenCentral.GroupArtifactVersion(MavenCentral.GroupId("org.webjars"), av._1, av._2))(av => (av.artifactId, av.version))

  val groupArtifactVersionPathCodec: PathCodec[MavenCentral.GroupArtifactVersion] =
    val base = PathCodec.empty / groupIdPathCodec / artifactIdPathCodec / versionPathCodec
    base.transform(gav => MavenCentral.GroupArtifactVersion(gav._1, gav._2, gav._3))(gav => (gav.groupId, gav.artifactId, gav.version))



  val routes: Routes[Client, Response] =
    Routes(
      // File routes - single handler that distinguishes between groupId and non-groupId paths
      Method.GET / "files" / string("p1") / string("p2") / string("p3") / trailing ->
        handlerTODO("files - implicit org.webjars"),
//        Handler.fromFileZIO(),
//
//
      /*
        handler { (p1: String, p2: String, p3: String, rest: Path, req: Request) =>
          if p1.startsWith("org.webjars") then
            // p1=groupId, p2=artifactId, p3=version, rest=file
            val file = pathToFile(rest)
            ZIO.scoped(fileHandler(p1, p2, p3, file, req))
          else
            // p1=artifactId, p2=version, p3 is first part of file path
            val restStr = pathToFile(rest)
            val file = if restStr.isEmpty then p3 else s"$p3/$restStr"
            ZIO.scoped(fileHandler("org.webjars", p1, p2, file, req))
        },
       */

      Method.OPTIONS / "files" / trailing -> fileOptionsHandler,

      Method.GET / "listfiles" / artifactVersionPathCodec -> Handler.fromFunctionHandler[(MavenCentral.GroupArtifactVersion, Request)](listFilesHandler),
      Method.GET / "listfiles" / groupArtifactVersionPathCodec -> Handler.fromFunctionHandler[(MavenCentral.GroupArtifactVersion, Request)](listFilesHandler),
      Method.GET / "numfiles" / artifactVersionPathCodec -> Handler.fromFunctionHandler[(MavenCentral.GroupArtifactVersion, Request)](numFilesHandler),
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
