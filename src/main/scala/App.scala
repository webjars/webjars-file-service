import zio.*
import zio.http.*
import zio.http.Header.{AccessControlAllowHeaders, AccessControlAllowMethods, AccessControlAllowOrigin, ContentType}
import zio.concurrent.ConcurrentMap
import zio.stream.ZStream

import java.io.{File, FileNotFoundException}
import java.net.{URL, URLEncoder}
import java.nio.file.Files
import java.util.jar.JarInputStream
import scala.util.Using

object App extends ZIOAppDefault:

  given CanEqual[Path, Path] = CanEqual.derived
  given CanEqual[Method, Method] = CanEqual.derived
  given CanEqual[Status, Status] = CanEqual.derived

  // Configuration
  val baseJarUrl = "https://repo1.maven.org/maven2/%s/%s/%s/%s-%s.jar"
  val webJarsPathPrefix = "META-INF/resources/webjars"

  // Types for state management
  type TmpDir = File
  type OngoingDownloads = ConcurrentMap[(String, String, String), Promise[Throwable, File]]

  // CORS middleware that adds Access-Control-Allow-Origin: * to all responses
  val corsMiddleware: HandlerAspect[Any, Unit] =
    HandlerAspect.interceptOutgoingHandler(Handler.fromFunction[Response] { response =>
      response.addHeader(AccessControlAllowOrigin.All)
    })

  // Download JAR file from Maven Central
  def downloadJar(groupId: String, artifactId: String, version: String): ZIO[TmpDir & OngoingDownloads & Client & Scope, Throwable, File] =
    for
      tmpDir <- ZIO.service[TmpDir]
      downloads <- ZIO.service[OngoingDownloads]
      jarFile = File(tmpDir, s"$groupId-$artifactId-$version.jar")
      result <- if jarFile.exists() then ZIO.succeed(jarFile)
                else
                  downloads.get((groupId, artifactId, version)).flatMap:
                    case Some(promise) =>
                      // Another download is in progress, wait for it
                      promise.await
                    case None =>
                      // Start a new download
                      for
                        promise <- Promise.make[Throwable, File]
                        _ <- downloads.put((groupId, artifactId, version), promise)
                        file <- doDownload(groupId, artifactId, version, jarFile, tmpDir)
                                  .tapBoth(
                                    err => promise.fail(err) *> downloads.remove((groupId, artifactId, version)),
                                    file => promise.succeed(file) *> downloads.remove((groupId, artifactId, version))
                                  )
                      yield file
    yield result

  private def doDownload(groupId: String, artifactId: String, version: String, jarFile: File, tmpDir: File): ZIO[Client & Scope, Throwable, File] =
    val urlStr = baseJarUrl.format(
      groupId.replace(".", "/"),
      artifactId,
      URLEncoder.encode(version, "UTF-8"),
      artifactId,
      URLEncoder.encode(version, "UTF-8")
    )
    for
      url <- ZIO.attempt(zio.http.URL.decode(urlStr)).flatMap(ZIO.fromEither(_)).mapError(e => new Exception(s"Invalid URL: $urlStr - $e"))
      response <- Client.request(Request.get(url))
      _ <- ZIO.when(response.status != Status.Ok)(
        ZIO.fail(new FileNotFoundException(s"JAR not found: $urlStr (status: ${response.status})"))
      )
      body <- response.body.asArray
      randomSuffix = scala.util.Random.alphanumeric.take(8).mkString
      tmpFile = File(tmpDir, s"$groupId-$artifactId-$version.jar-tmp-$randomSuffix")
      _ <- ZIO.attemptBlocking(Files.write(tmpFile.toPath, body))
      finalFile <- ZIO.attemptBlocking {
        if jarFile.exists() then
          tmpFile.delete()
          jarFile
        else
          Files.move(tmpFile.toPath, jarFile.toPath).toFile
      }
    yield finalFile

  // Fetch list of files in a WebJar
  def fetchFileList(groupId: String, artifactId: String, version: String): ZIO[TmpDir & OngoingDownloads & Client & Scope, Throwable, List[String]] =
    for
      jarFile <- downloadJar(groupId, artifactId, version)
      files <- ZIO.attemptBlocking {
        Using.resource(new JarInputStream(Files.newInputStream(jarFile.toPath))) { jarInputStream =>
          Iterator.continually(jarInputStream.getNextJarEntry)
            .takeWhile(_ != null)
            .filterNot(_.isDirectory)
            .map(_.getName)
            .filter(_.startsWith(webJarsPathPrefix))
            .toList
        }
      }
    yield files

  // Read a specific file from a JAR
  def readFileFromJar(jarFile: File, entryPath: String): ZIO[Any, Throwable, Array[Byte]] =
    ZIO.attemptBlocking {
      val jarUrl = new URL(s"jar:file:${jarFile.getAbsolutePath}!/$entryPath")
      Using.resource(jarUrl.openStream()) { is =>
        is.readAllBytes()
      }
    }

  // Determine MIME type from file extension
  def getMimeType(fileName: String): MediaType =
    val ext = fileName.lastIndexOf('.') match
      case -1 => ""
      case i => fileName.substring(i + 1).toLowerCase
    ext match
      case "js" => MediaType.application.javascript
      case "mjs" => MediaType.application.javascript
      case "css" => MediaType.text.css
      case "html" | "htm" => MediaType.text.html
      case "json" => MediaType.application.json
      case "map" => MediaType.application.json
      case "xml" => MediaType.application.xml
      case "svg" => MediaType.image.`svg+xml`
      case "png" => MediaType.image.png
      case "jpg" | "jpeg" => MediaType.image.jpeg
      case "gif" => MediaType.image.gif
      case "ico" => MediaType.image.`x-icon`
      case "woff" => MediaType.application.`font-woff`
      case "woff2" => MediaType("font", "woff2")
      case "ttf" => MediaType("font", "ttf")
      case "eot" => MediaType.application.`vnd.ms-fontobject`
      case "otf" => MediaType("font", "otf")
      case "txt" => MediaType.text.plain
      case "md" => MediaType.text.plain
      case "ts" => MediaType.text.plain.copy(subType = "typescript")
      case _ => MediaType.application.`octet-stream`

  // File handler
  def fileHandler(groupId: String, artifactId: String, version: String, file: String): ZIO[TmpDir & OngoingDownloads & Client & Scope, Response, Response] =
    val result = for
      jarFile <- downloadJar(groupId, artifactId, version)
      fileList <- fetchFileList(groupId, artifactId, version)
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
      content <- readFileFromJar(jarFile, entryPath)
    yield
      val mimeType = getMimeType(file)
      val etag = java.security.MessageDigest.getInstance("SHA-1")
        .digest(content)
        .map("%02x".format(_))
        .mkString
      Response(
        status = Status.Ok,
        headers = Headers(
          ContentType(mimeType),
          Header.Custom("Cache-Control", "public, max-age=31536000, immutable"),
          Header.ETag.Strong(etag)
        ),
        body = Body.fromArray(content)
      )

    result.catchAll {
      case _: FileNotFoundException =>
        ZIO.fail(Response.notFound(s"WebJar Not Found $groupId : $artifactId : $version"))
      case e: Throwable =>
        ZIO.fail(Response.internalServerError(s"Could not find WebJar ($groupId : $artifactId : $version)\n${e.getMessage}"))
    }

  // List files handler
  def listFilesHandler(groupId: String, artifactId: String, version: String): ZIO[TmpDir & OngoingDownloads & Client & Scope, Response, Response] =
    fetchFileList(groupId, artifactId, version)
      .map { files =>
        val json = files.map(f => s""""$f"""").mkString("[", ",", "]")
        Response.json(json)
      }
      .catchAll {
        case _: FileNotFoundException =>
          ZIO.fail(Response.notFound(s"WebJar Not Found $groupId : $artifactId : $version"))
        case e: Throwable =>
          ZIO.fail(Response.internalServerError(e.getMessage))
      }

  // Num files handler
  def numFilesHandler(groupId: String, artifactId: String, version: String): ZIO[TmpDir & OngoingDownloads & Client & Scope, Response, Response] =
    fetchFileList(groupId, artifactId, version)
      .map(files => Response.text(files.size.toString))
      .catchAll {
        case _: FileNotFoundException =>
          ZIO.fail(Response.notFound(s"WebJar Not Found $groupId : $artifactId : $version"))
        case e: Throwable =>
          ZIO.fail(Response.internalServerError(e.getMessage))
      }

  // CORS preflight handler for file routes
  val fileOptionsHandler: Handler[Any, Nothing, Request, Response] =
    Handler.fromFunction[Request] { _ =>
      Response.ok.addHeader(AccessControlAllowHeaders.All)
    }

  // CORS preflight handler for other routes
  val corsPreflightHandler: Handler[Any, Nothing, Request, Response] =
    Handler.fromFunction[Request] { _ =>
      Response.ok
        .addHeader(AccessControlAllowOrigin.All)
        .addHeader(AccessControlAllowMethods(Method.GET))
    }

  // robots.txt content
  val robotsTxt = """User-agent: *
                    |Disallow: /files/
                    |Disallow: /listfiles/
                    |""".stripMargin

  // Helper to convert Path to file string (removes leading slash)
  private def pathToFile(path: Path): String =
    val s = path.toString
    if s.startsWith("/") then s.drop(1) else s

  // Routes
  val routes: Routes[TmpDir & OngoingDownloads & Client, Response] =
    Routes(
      // File routes - single handler that distinguishes between groupId and non-groupId paths
      Method.GET / "files" / string("p1") / string("p2") / string("p3") / trailing ->
        handler { (p1: String, p2: String, p3: String, rest: Path, _: Request) =>
          if p1.startsWith("org.webjars") then
            // p1=groupId, p2=artifactId, p3=version, rest=file
            val file = pathToFile(rest)
            ZIO.scoped(fileHandler(p1, p2, p3, file))
          else
            // p1=artifactId, p2=version, p3 is first part of file path
            val restStr = pathToFile(rest)
            val file = if restStr.isEmpty then p3 else s"$p3/$restStr"
            ZIO.scoped(fileHandler("org.webjars", p1, p2, file))
        },

      // OPTIONS for files (CORS preflight)
      Method.OPTIONS / "files" / trailing -> fileOptionsHandler,

      // List files routes
      Method.GET / "listfiles" / string("artifactId") / string("version") ->
        handler { (artifactId: String, version: String, _: Request) =>
          ZIO.scoped(listFilesHandler("org.webjars", artifactId, version))
        },

      Method.GET / "listfiles" / string("groupId") / string("artifactId") / string("version") ->
        handler { (groupId: String, artifactId: String, version: String, _: Request) =>
          ZIO.scoped(listFilesHandler(groupId, artifactId, version))
        },

      // Num files routes
      Method.GET / "numfiles" / string("artifactId") / string("version") ->
        handler { (artifactId: String, version: String, _: Request) =>
          ZIO.scoped(numFilesHandler("org.webjars", artifactId, version))
        },

      Method.GET / "numfiles" / string("groupId") / string("artifactId") / string("version") ->
        handler { (groupId: String, artifactId: String, version: String, _: Request) =>
          ZIO.scoped(numFilesHandler(groupId, artifactId, version))
        },

      // robots.txt routes
      Method.GET / "robots.txt" -> Handler.fromResponse(Response.text(robotsTxt)),
      Method.GET / "files" / "robots.txt" -> Handler.fromResponse(Response.text(robotsTxt)),

      // Catch-all OPTIONS for CORS preflight
      Method.OPTIONS / trailing -> corsPreflightHandler,
    )

  // Server configuration
  def serverLayer: ZLayer[Any, Throwable, Server] =
    Server.defaultWithPort(9000)

  // Temp directory layer
  val tmpDirLayer: ZLayer[Any, Nothing, TmpDir] =
    ZLayer.succeed(Files.createTempDirectory("webjars").toFile.nn)

  // Ongoing downloads layer
  val downloadsLayer: ZLayer[Any, Nothing, OngoingDownloads] =
    ZLayer.fromZIO(ConcurrentMap.empty[(String, String, String), Promise[Throwable, File]])

  override val run =
    Server.serve(routes @@ corsMiddleware).provide(
      serverLayer,
      Client.default,
      tmpDirLayer,
      downloadsLayer,
    )
