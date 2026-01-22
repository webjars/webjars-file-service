import App.given
import com.jamesward.zio_mavencentral.MavenCentral
import zio.*
import zio.http.*
import zio.stream.ZPipeline
import zio.test.*

object AppSpec extends ZIOSpecDefault:

  def spec = suite("All tests")(
    suite("file endpoint")(
      test("returns 200 for valid file with groupId") {
        val request = Request.get(URL.decode("/files/org.webjars/jquery/3.2.1/jquery.js").toOption.get)
        for
          response <- App.routes.runZIO(request)
        yield
          assertTrue(response.status == Status.Ok, response.headers.get(Header.ContentType).exists(_.mediaType.subType.contains("javascript")), response.headers.get("Cache-Control").exists(_.contains("max-age=31536000")), response.headers.get(Header.ETag).isDefined)
      },

      test("returns 404 for non-existent file") {
        val request = Request.get(URL.decode("/files/org.webjars/jquery/3.2.1/nonexistent.js").toOption.get)
        for
          response <- App.routes.runZIO(request)
        yield assertTrue(response.status == Status.NotFound)
      },

      test("returns 404 for non-existent webjar version") {
        val request = Request.get(URL.decode("/files/org.webjars/jquery/0.0.0/jquery.js").toOption.get)
        for
          response <- App.routes.runZIO(request)
        yield
          assertTrue(response.status == Status.NotFound, response.headers.get("Cache-Control").isEmpty || !response.headers.get("Cache-Control").exists(_.contains("max-age")))
      },

      test("works with org.webjars.npm groupId") {
        val request = Request.get(URL.decode("/files/org.webjars.npm/highlightjs__cdn-assets/11.4.0/highlight.min.js").toOption.get)
        for
          response <- App.routes.runZIO(request)
          body <- response.body.asString
        yield
          assertTrue(response.status == Status.Ok, body.contains("hljs"))
      },

      test("works with webjars where contents are not versioned (bowergithub)") {
        val request = Request.get(URL.decode("/files/org.webjars.bowergithub.polymer/polymer/2.8.0/types/polymer.d.ts").toOption.get)
        for
          response <- App.routes.runZIO(request)
        yield assertTrue(response.status == Status.Ok)
      },

      test("works without explicit groupId (defaults to org.webjars)") {
        val request = Request.get(URL.decode("/files/jquery/3.2.1/jquery.js").toOption.get)
        for
          response <- App.routes.runZIO(request)
        yield assertTrue(response.status == Status.PermanentRedirect, response.header(Header.Location).map(_.renderedValue).contains("/files/org.webjars/jquery/3.2.1/jquery.js"))
      },

      test("returns correct content-type for CSS files") {
        val request = Request.get(URL.decode("/files/org.webjars/bootstrap/3.3.7/css/bootstrap.min.css").toOption.get)
        for
          response <- App.routes.runZIO(request)
        yield
          assertTrue(response.status == Status.Ok, response.headers.get(Header.ContentType).exists(_.mediaType.subType.contains("css")))
      },
    ),

    suite("conditional requests")(
      test("returns 304 when ETag matches") {
        val path = "/files/org.webjars/jquery/3.2.1/jquery.js"
        val request1 = Request.get(URL.decode(path).toOption.get)
        for
          response1 <- App.routes.runZIO(request1)
          etag <- ZIO.fromOption(response1.headers.get(Header.ETag)).orElseFail(new Exception("ETag missing"))
          request2 = Request.get(URL.decode(path).toOption.get).addHeaders(Headers(Header.Custom("If-None-Match", etag.renderedValue)))
          response2 <- App.routes.runZIO(request2)
        yield
          assertTrue(response1.status == Status.Ok, response2.status == Status.NotModified)
      },

      test("returns 304 when If-Modified-Since matches") {
        val path = "/files/org.webjars/jquery/3.2.1/jquery.js"
        val request1 = Request.get(URL.decode(path).toOption.get)
        for
          response1 <- App.routes.runZIO(request1)
          lastModified <- ZIO.fromOption(response1.headers.get(Header.LastModified)).orElseFail(new Exception("Last-Modified missing"))
          // Add a small buffer to ensure the time is not before lastModified due to precision loss
          ifModifiedSince = lastModified.value.plusSeconds(1)
          request2 = Request.get(URL.decode(path).toOption.get).addHeader(Header.IfModifiedSince(ifModifiedSince))
          response2 <- App.routes.runZIO(request2)
        yield
          assertTrue(response1.status == Status.Ok, response2.status == Status.NotModified)
      },

      test("returns 200 when If-Modified-Since is before last modified") {
        val path = "/files/org.webjars/jquery/3.2.1/jquery.js"
        val request1 = Request.get(URL.decode(path).toOption.get)
        for
          response1 <- App.routes.runZIO(request1)
          lastModified <- ZIO.fromOption(response1.headers.get(Header.LastModified)).orElseFail(new Exception("Last-Modified missing"))
          // One hour before
          pastDate = lastModified.value.minusHours(1)
          request2 = Request.get(URL.decode(path).toOption.get).addHeader(Header.IfModifiedSince(pastDate))
          response2 <- App.routes.runZIO(request2)
        yield
          assertTrue(response1.status == Status.Ok, response2.status == Status.Ok)
      },
    ),

    suite("listfiles endpoint")(
      test("redirects to path with groupId") {
        val request = Request.get(URL.decode("/listfiles/jquery/3.2.1").toOption.get)
        for
          response <- App.routes.runZIO(request)
        yield
          assertTrue(response.status == Status.PermanentRedirect, response.header(Header.Location).map(_.renderedValue).contains("/listfiles/org.webjars/jquery/3.2.1"))
      },

      test("works with explicit groupId") {
        val request = Request.get(URL.decode("/listfiles/org.webjars/jquery/3.2.1").toOption.get)
        for
          response <- App.routes.runZIO(request)
          body <- response.body.asString
        yield
          assertTrue(response.status == Status.Ok, body.startsWith("["), body.contains("jquery.js"))
      },

      test("returns 404 for non-existent webjar") {
        val request = Request.get(URL.decode("/listfiles/org.webjars/nonexistent-webjar/1.0.0").toOption.get)
        for
          response <- App.routes.runZIO(request)
        yield assertTrue(response.status == Status.NotFound)
      },

      test("has CORS header") {
        val request = Request.get(URL.decode("/listfiles/org.webjars/jquery/3.2.1").toOption.get)
        for
          response <- (App.routes @@ App.corsMiddleware).runZIO(request)
        yield assertTrue(response.headers.get(Header.AccessControlAllowOrigin).isDefined)
      },
    ),

    suite("numfiles endpoint")(
      test("redirects to path with groupId") {
        val request = Request.get(URL.decode("/numfiles/jquery/3.2.1").toOption.get)
        for
          response <- App.routes.runZIO(request)
        yield
          assertTrue(response.status == Status.PermanentRedirect, response.header(Header.Location).map(_.renderedValue).contains("/numfiles/org.webjars/jquery/3.2.1"))
      },

      test("works with explicit groupId") {
        val request = Request.get(URL.decode("/numfiles/org.webjars/jquery/3.2.1").toOption.get)
        for
          response <- App.routes.runZIO(request)
          body <- response.body.asString
        yield
          assertTrue(response.status == Status.Ok, body.trim == "4")
      },
    ),

    suite("CORS")(
      test("OPTIONS /files/* returns CORS headers") {
        val request = Request(method = Method.OPTIONS, url = URL.decode("/files/org.webjars/jquery/3.2.1/jquery.js").toOption.get)
        for
          response <- (App.routes @@ App.corsMiddleware).runZIO(request)
        yield
          assertTrue(response.status == Status.Ok, response.headers.get(Header.AccessControlAllowOrigin).isDefined)
      },

      test("OPTIONS on any path returns CORS headers") {
        val request = Request(method = Method.OPTIONS, url = URL.decode("/some/random/path").toOption.get)
        for
          response <- (App.routes @@ App.corsMiddleware).runZIO(request)
        yield
          assertTrue(response.status == Status.Ok, response.headers.get(Header.AccessControlAllowOrigin).isDefined)
      },

      test("GET responses include CORS header") {
        val request = Request.get(URL.decode("/files/org.webjars/jquery/3.2.1/jquery.js").toOption.get)
        for
          response <- (App.routes @@ App.corsMiddleware).runZIO(request)
        yield assertTrue(response.headers.get(Header.AccessControlAllowOrigin).isDefined)
      },
    ),

    suite("robots.txt")(
      test("returns robots.txt at /robots.txt") {
        val request = Request.get(URL.decode("/robots.txt").toOption.get)
        for
          response <- App.routes.runZIO(request)
          body <- response.body.asString
        yield
          assertTrue(response.status == Status.Ok, body.contains("User-agent"))
      },

      test("returns robots.txt at /files/robots.txt") {
        val request = Request.get(URL.decode("/files/robots.txt").toOption.get)
        for
          response <- App.routes.runZIO(request)
          body <- response.body.asString
        yield
          assertTrue(response.status == Status.Ok, body.contains("User-agent"))
      },
    ),

    suite("fetchFileList")(
      test("returns list of files in webjar") {
        for
          stream <- App.fetchFileList(MavenCentral.GroupArtifactVersion(MavenCentral.GroupId("org.webjars"), MavenCentral.ArtifactId("jquery"), MavenCentral.Version("3.2.1")))
          files <- stream.contents.runCollect
        yield
          assertTrue(files.exists(_.contains("jquery.js")))
      },
    ),

    suite("readFileFromJar")(
      test("file exists") {
        val url = App.webJarUrl(MavenCentral.GroupArtifactVersion(MavenCentral.GroupId("org.webjars"), MavenCentral.ArtifactId("jquery"), MavenCentral.Version("3.2.1")))
        for
          file <- App.readFileFromJar(url, App.webJarsPathPrefix + "/jquery/3.2.1/jquery.js").via(ZPipeline.utf8Decode).runHead
        yield
          assertTrue(file.exists(_.contains("jQuery")))
      },
      test("file not found") {
        val url = App.webJarUrl(MavenCentral.GroupArtifactVersion(MavenCentral.GroupId("org.webjars"), MavenCentral.ArtifactId("jquery"), MavenCentral.Version("3.2.1")))
        for
          error <- App.readFileFromJar(url, App.webJarsPathPrefix + "/jquery/3.2.1/asdf.js").runCollect.flip
        yield
          assertTrue(error.isInstanceOf[java.io.FileNotFoundException])
      },
      test("webjar not found") {
        val url = App.webJarUrl(MavenCentral.GroupArtifactVersion(MavenCentral.GroupId("org.webjars"), MavenCentral.ArtifactId("jquery"), MavenCentral.Version("0.0.0")))
        for
          error <- App.readFileFromJar(url, App.webJarsPathPrefix + "/jquery/0.0.0/jquery.js").runCollect.flip
        yield
          assertTrue(error.isInstanceOf[java.io.FileNotFoundException])
      },
    ),

    suite("gzip compression")(
      test("returns gzip encoded response when client accepts gzip") {
        for
          port <- Server.install(App.routes @@ App.corsMiddleware)
          url <- ZIO.fromEither(URL.decode(s"http://localhost:$port/files/org.webjars/jquery/3.2.1/jquery.js"))
          request = Request.get(url).addHeader(Header.AcceptEncoding(Header.AcceptEncoding.GZip()))
          // Use a client that doesn't automatically decompress
          response <- ZClient.batched(request)
          body <- response.body.asArray
        yield
          assertTrue(response.status == Status.Ok, response.headers.get(Header.ContentEncoding).exists(_.renderedValue.contains("gzip")), body.length >= 2 && body(0) == 0x1f.toByte && body(1) == 0x8b.toByte)
      },

      test("returns uncompressed response when client does not accept gzip") {
        for
          port <- Server.install(App.routes @@ App.corsMiddleware)
          url <- ZIO.fromEither(URL.decode(s"http://localhost:$port/files/org.webjars/jquery/3.2.1/jquery.js"))
          request = Request.get(url)
          response <- ZClient.batched(request)
          body <- response.body.asString
        yield
          assertTrue(response.status == Status.Ok, response.headers.get(Header.ContentEncoding).isEmpty, body.contains("jQuery"))
      },

      test("gzip compressed response can be decompressed") {
        for
          port <- Server.install(App.routes @@ App.corsMiddleware)
          url <- ZIO.fromEither(URL.decode(s"http://localhost:$port/files/org.webjars/jquery/3.2.1/jquery.js"))
          request = Request.get(url).addHeader(Header.AcceptEncoding(Header.AcceptEncoding.GZip()))
          response <- ZClient.batched(request)
          compressedBody <- response.body.asArray
          decompressedBody <- ZIO.attemptBlocking {
            val bais = new java.io.ByteArrayInputStream(compressedBody)
            val gzis = new java.util.zip.GZIPInputStream(bais)
            new String(gzis.readAllBytes(), "UTF-8")
          }
        yield
          assertTrue(response.status == Status.Ok, decompressedBody.contains("jQuery"))
      },
    ).provide(Client.default, App.serverLayer) @@ TestAspect.sequential, // todo: random server port and shared server (can't do that because Server.install duplicates routes)

  ).provide(Client.default, Scope.default)
