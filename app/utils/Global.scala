package utils

import play.api.mvc.WithFilters
import play.api.{Application, Play}
import play.filters.gzip.GzipFilter
import shade.memcached.{AuthConfiguration, Configuration, Memcached}

object Global extends WithFilters(new GzipFilter(1000 * 1024)) {

  lazy val memcached = {
    val maybeUsernamePassword = for {
      username <- Play.current.configuration.getString("memcached.username")
      password <- Play.current.configuration.getString("memcached.password")
    } yield (username, password)

    val authConfig = maybeUsernamePassword.map {
      case (username, password) =>
        AuthConfiguration(username, password)
    }

    val config = Configuration(Play.current.configuration.getString("memcached.servers").get, authConfig)

    Memcached(config)(scala.concurrent.ExecutionContext.global)
  }

  override def onStop(app: Application): Unit = {
    memcached.close()
    super.onStop(app)
  }

}