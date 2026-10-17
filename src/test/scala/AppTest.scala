import zio.*

/**
 * Development server (`./sbt Test/run`). Uses real S3 when `S3_BUCKET` and
 * `AWS_ACCESS_KEY_ID` are set, exactly like production; otherwise starts a
 * Floci container and serves from its simulated S3.
 */
object AppTest extends ZIOAppDefault:

  override val run =
    for
      bucket <- System.env("S3_BUCKET")
      keyId  <- System.env("AWS_ACCESS_KEY_ID")
      objectStore = (bucket, keyId) match
        case (Some(_), Some(_)) => S3ObjectStore.fromEnv
        case _                  => Floci.objectStore
      _ <- ZIO.logInfo(s"object_store=${if bucket.isDefined && keyId.isDefined then "s3" else "floci"}")
      _ <- App.serve(objectStore)
    yield ()
