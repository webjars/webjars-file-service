package utils

import javax.inject.Inject

import com.google.inject.AbstractModule
import play.api.{Configuration => PlayConfig}
import shade.memcached.{AuthConfiguration, Configuration, Memcached}

class MemcacheModule extends AbstractModule {

  override def configure() = {
    bind(classOf[Memcache]).to(classOf[MemcacheImpl])
  }

}

trait Memcache {
  val connection: Memcached
}

class MemcacheImpl @Inject() (playConfig: PlayConfig) extends Memcache {

  lazy val connection = {
    val maybeUsernamePassword = for {
      username <- playConfig.getString("memcached.username")
      password <- playConfig.getString("memcached.password")
    } yield (username, password)

    val authConfig = maybeUsernamePassword.map {
      case (username, password) =>
        AuthConfiguration(username, password)
    }

    val config = Configuration(playConfig.getString("memcached.servers").get, authConfig)

    Memcached(config)(scala.concurrent.ExecutionContext.global)
  }

}