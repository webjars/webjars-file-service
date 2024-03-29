package controllers

import com.dimafeng.testcontainers.GenericContainer
import org.scalatestplus.play.PlaySpec
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.http.MimeTypes
import play.api.{Environment, Mode}
import play.api.inject.ApplicationLifecycle
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.test.FakeRequest
import play.api.test.Helpers._

import scala.concurrent.Future
import scala.util.{Random, Try}

// todo: test far future expires
class ApplicationSpec extends PlaySpec with GuiceOneAppPerSuite {

  override def fakeApplication(): play.api.Application = {
    val memcached = GenericContainer("memcached", Seq(11211))
    memcached.start()

    val app = GuiceApplicationBuilder(environment = Environment.simple(mode = Mode.Prod))
      .configure("play.http.secret.key" -> Random.nextString(64))
      .configure("memcached.host" -> (memcached.host + ":" + memcached.mappedPort(11211)))
      .build()

    app.injector.instanceOf[ApplicationLifecycle].addStopHook { () =>
      Future.fromTry(Try(memcached.stop()))
    }

    app
  }

  lazy val appController = app.injector.instanceOf[Application]

  implicit lazy val materializer = app.materializer

  "file" must {
    "work" in {
      val response = appController.file("org.webjars", "jquery", "3.2.1", "jquery.js")(FakeRequest())
      status(response) must equal (OK)
      contentType(response).value must equal (MimeTypes.JAVASCRIPT)
      header(CACHE_CONTROL, response).value must equal ("public, max-age=31536000, immutable")
      header(ETAG, response) must not be empty
    }
    "not found with a non-existent file" in {
      val response = appController.file("org.webjars", "jquery", "3.2.1", "asdf")(FakeRequest())
      status(response) must equal (NOT_FOUND)
    }
    "not found with a non-existent webjar" in {
      val response = appController.file("org.webjars", "jquery", "0.0.0", "jquery.js")(FakeRequest())
      header(CACHE_CONTROL, response) must be (empty)
      status(response) must equal (NOT_FOUND)
    }
    "not find the wrong file" in {
      val response = appController.file("org.webjars.npm", "highlightjs__cdn-assets", "11.4.0", "highlight.min.js")(FakeRequest())
      status(response) must equal (OK)
      contentAsString(response) must include ("var hljs=function(){\"use strict\";var e={exports:{}};function n(e){")
    }
    "work with webjars where contents are not versioned" in {
      val response = appController.file("org.webjars.bowergithub.polymer", "polymer", "2.8.0", "types/polymer.d.ts")(FakeRequest())
      status(response) must equal (OK)
    }
    "work with the groupId" in {
      val result = route(app, FakeRequest(GET, "/files/org.webjars/jquery/3.2.1/jquery.js")).get
      status(result) must equal (OK)
    }
    "work without the groupId" in {
      val result = route(app, FakeRequest(GET, "/files/jquery/3.2.1/jquery.js")).get
      status(result) must equal (OK)
    }
    "work with bowergithub" in {
      val result = route(app, FakeRequest(GET, "/files/org.webjars.bowergithub.polymer/polymer/2.8.0/types/polymer.d.ts")).get
      status(result) must equal (OK)
    }
  }

}
