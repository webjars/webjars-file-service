import com.dimafeng.testcontainers.GenericContainer
import zio.*
import zio.direct.*
import zio.redis.{CodecSupplier, Redis, RedisConfig, RedisExecutor}
import zio.schema.Schema
import zio.schema.codec.*
import zio.test.*

object MavenCentralSpec extends ZIOSpecDefault:

  val container = ZIO.acquireRelease {
    ZIO.attempt {
      val container = GenericContainer("redis", Seq(6379))
      container.start()
      container
    }.orDie
  } { container =>
    ZIO
      .attempt(container.stop())
      .ignoreLogged
  }

  val containerLayer = ZLayer.scoped(container)

  object ProtobufCodecSupplier extends CodecSupplier:
    def get[A: Schema]: BinaryCodec[A] = ProtobufCodec.protobufCodec

  val codecLayer = ZLayer.succeed[CodecSupplier](ProtobufCodecSupplier)

  val redisConfigLayer: ZLayer[Any, Nothing, RedisConfig] =
    ZLayer.scoped:
      container.map:
        redisContainer =>
          RedisConfig(redisContainer.container.getHost, redisContainer.container.getFirstMappedPort)

  def spec = suite("MavenCentral")(
    test("all") {
      defer:
        val redis = ZIO.service[Redis].run
        redis.set("myKey", 8L, Some(1.minutes)).run
        val result = redis.get("myKey").returning[Long].run
        assertTrue(result.get == 8L)
    }
  ).provideShared(
    Redis.layer,
    RedisExecutor.layer,
    codecLayer,
    redisConfigLayer
  )

