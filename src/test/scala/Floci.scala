import io.floci.testcontainers.FlociContainer
import zio.*
import zio.http.URL

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
            // Signature validation stays off: Floci doesn't check the secret, and it
            // builds the canonical URI from the raw wire path where real S3
            // re-encodes it, so it would reject correct signatures for keys with
            // `*`, `:` or `@`. SigV4 is covered by SigV4Spec and the real-S3 suite.
            val c = FlociContainer(image).disableAllServices().withS3Config(_.enabled(true))
            c.start()
            (c, Some((1, c)))
    )(_ =>
      shared.updateZIO:
        case Some((1, c))     => ZIO.attemptBlocking(c.stop()).catchAllCause(ZIO.logWarningCause("Floci stop failed", _)).as(None)
        case Some((users, c)) => ZIO.succeed(Some((users - 1, c)))
        case None             => ZIO.none
    )

  /** An [[S3ObjectStore]] on a fresh, uniquely named bucket in the shared Floci container. */
  val s3: ZIO[Scope, Throwable, S3ObjectStore] =
    for
      c        <- container
      endpoint <- ZIO.fromEither(URL.decode(c.getEndpoint))
      bucket    = BucketName(s"webjars-${UUID.randomUUID()}")
      config    = S3ObjectStore.Config(
                    bucket,
                    AwsRegion(c.getRegion),
                    S3ObjectStore.Addressing.PathStyle(endpoint),
                    AwsCredentials(c.getAccessKey, c.getSecretKey, None),
                  )
      store    <- S3ObjectStore.scoped(config)
      _        <- store.createBucket
      _        <- ZIO.logInfo(s"floci endpoint=${c.getEndpoint} bucket=${bucket.value}")
    yield store

  val objectStore: ZLayer[Any, Throwable, ObjectStore] =
    ZLayer.scoped(s3)
