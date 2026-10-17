import io.floci.testcontainers.FlociContainer
import software.amazon.awssdk.regions.Region
import zio.*

import java.net.URI
import java.util.UUID

/** Local S3 emulator (Floci) for tests and for `Test/run` without AWS credentials. */
object Floci:

  private val image = "floci/floci:2.3.0"

  /**
   * One container shared by every spec that is using it at the same time:
   * starting containers in parallel races rootless Docker's port manager
   * (`bind: address already in use`). Reference-counted rather than a lazy
   * singleton because sbt 2 keeps test JVMs alive between runs, so a JVM-exit
   * reaper would leak a container per run. `modifyZIO` serializes starts and
   * stops. Specs stay isolated by each getting its own bucket.
   */
  private val shared: Ref.Synchronized[Option[(Int, FlociContainer)]] =
    Unsafe.unsafely(Ref.Synchronized.unsafe.make(Option.empty[(Int, FlociContainer)]))

  private val container: ZIO[Scope, Throwable, FlociContainer] =
    ZIO.acquireRelease(
      shared.modifyZIO:
        case Some((users, c)) => ZIO.succeed((c, Some((users + 1, c))))
        case None =>
          ZIO.attemptBlocking:
            val c = FlociContainer(image).disableAllServices().withS3Config(_.enabled(true))
            c.start()
            (c, Some((1, c)))
    )(_ =>
      shared.updateZIO:
        case Some((1, c))     => ZIO.attemptBlocking(c.stop()).catchAllCause(ZIO.logWarningCause("Floci stop failed", _)).as(None)
        case Some((users, c)) => ZIO.succeed(Some((users - 1, c)))
        case None             => ZIO.none
    )

  /** An [[ObjectStore]] on a fresh, uniquely named bucket in the shared Floci container. */
  val objectStore: ZLayer[Any, Throwable, ObjectStore] =
    ZLayer.scoped:
      for
        c      <- container
        s3     <- zio.s3.liveZIO(Region.of(c.getRegion).nn, zio.s3.providers.basic(c.getAccessKey, c.getSecretKey), Some(URI(c.getEndpoint)), Some(true))
                    .build.map(_.get[zio.s3.S3])
        bucket  = BucketName(s"webjars-${UUID.randomUUID()}")
        _      <- s3.createBucket(bucket.value)
        _      <- ZIO.logInfo(s"floci endpoint=${c.getEndpoint} bucket=${bucket.value}")
      yield S3ObjectStore(s3, bucket)
