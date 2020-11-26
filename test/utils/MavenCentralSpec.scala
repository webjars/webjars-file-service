package utils

import com.dimafeng.testcontainers.GenericContainer
import org.scalatestplus.play.PlaySpec
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.Application
import play.api.inject.ApplicationLifecycle
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.test.Helpers._

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Try

class MavenCentralSpec extends PlaySpec with GuiceOneAppPerSuite {

  override def fakeApplication(): Application = {
    val memcached = GenericContainer("memcached", Seq(11211))
    memcached.start()

    val app = new GuiceApplicationBuilder()
      .configure("memcached.host" -> (memcached.host + ":" + memcached.mappedPort(11211)))
      .build()

    app.injector.instanceOf[ApplicationLifecycle].addStopHook { () =>
      Future.fromTry(Try(memcached.stop()))
    }

    app
  }

  lazy val mavenCentral = app.injector.instanceOf[MavenCentral]

  "getFile" must {
    "work" in {
      assert(mavenCentral.getFile("org.webjars", "jquery", "3.2.1").isSuccess)
      assert(mavenCentral.getFile("org.webjars", "jquery", "0.0.1").isFailure)
      assert(mavenCentral.getFile("org.webjars", "asdfqwer", "0.0.1").isFailure)
    }
    "work in parallel" in {
      val f1 = Future(mavenCentral.getFile("org.webjars", "jquery", "3.2.0"))
      val f2 = Future(mavenCentral.getFile("org.webjars", "jquery", "3.2.0"))
      assert(await(Future.sequence(Seq(f1, f2))).forall(_.isSuccess))
    }
  }

  "numFiles" must {
    "work" in {
      await(mavenCentral.numFiles("org.webjars", "jquery", "3.2.1")) must equal (4)
    }
  }

}
