import zio.*
import zio.http.MediaType
import zio.test.*

import java.util.UUID

/**
 * The S3 client against Floci and, when
 * `S3_INTEGRATION=true` with `S3_BUCKET` and AWS credentials set (e.g.
 * `set -a; . ./.env.local; set +a`), against real S3. The real-S3 suite
 * writes a handful of small objects under `test/<uuid>/`.
 */
object S3ObjectStoreSpec extends ZIOSpecDefault:

  /** Keys that stress SigV4's canonical URI: reserved/unsafe characters, spaces, '%', non-ASCII. */
  private val awkwardNames = List(
    "plain.js",
    "with space and+plus.js",
    "reserved!*'()@:$&,;=~.js",
    "percent%20literal%.js",
    "ünïcødé/日本語.css",
    "nested/a/b/c/d.js",
  )

  private def common(prefix: UIO[String]) = List(
    test("write then get and read round-trip, for awkward keys"):
      for
        store <- ZIO.service[ObjectStore]
        p     <- prefix
        keys   = awkwardNames.map(n => StoreKey(s"$p/$n"))
        _     <- ZIO.foreachParDiscard(keys)(k => store.write(k, k.value.getBytes("UTF-8"), Some(MediaType.application.javascript)))
        read  <- ZIO.foreach(keys)(store.read)
        sizes <- ZIO.foreach(keys)(k => ZIO.scoped(store.get(k).map(_.flatMap(_.contentLength))))
      yield assertTrue(
        read == keys.map(k => Some(Chunk.fromArray(k.value.getBytes("UTF-8")))),
        sizes == keys.map(k => Some(k.value.getBytes("UTF-8").length.toLong)),
      )
    ,
    test("a multi-megabyte object streams back intact"):
      for
        store <- ZIO.service[ObjectStore]
        p     <- prefix
        bytes <- Random.nextBytes(5 * 1024 * 1024 + 17).map(_.toArray)
        key    = StoreKey(s"$p/big.bin")
        _     <- store.write(key, bytes, None)
        back  <- ZIO.scoped(store.get(key).some.flatMap(_.body.runCollect))
      yield assertTrue(back == Chunk.fromArray(bytes))
    ,
    test("missing objects are None, not errors"):
      for
        store <- ZIO.service[ObjectStore]
        p     <- prefix
        read  <- store.read(StoreKey(s"$p/missing"))
        got   <- ZIO.scoped(store.get(StoreKey(s"$p/missing")))
      yield assertTrue(read.isEmpty, got.isEmpty)
    ,
  )

  // Floci doesn't validate signatures (see `Floci`), so only against real S3.
  private def signatureChecks(prefix: UIO[String]) = List(
    test("bad credentials surface as a typed S3Error"):
      for
        store <- ZIO.serviceWithZIO[ObjectStore]:
                   case s: S3ObjectStore => ZIO.succeed(s)
                   case other            => ZIO.fail(IllegalStateException(s"expected S3ObjectStore, got $other"))
        // A fresh instance: `copy` would share the cached signing key.
        bad   <- S3ObjectStore.make(store.client, store.config.copy(credentials = store.config.credentials.copy(secretAccessKey = "wrong")))
        p     <- prefix
        error <- bad.write(StoreKey(s"$p/denied"), Array[Byte](1), None).flip
      yield assertTrue(
        error match
          case e: S3ObjectStore.S3Error => e.status == 403 && e.code.contains("SignatureDoesNotMatch")
          case _                        => false
      )
    ,
  )

  private val realS3: ZLayer[Any, Throwable, ObjectStore] = S3ObjectStore.fromEnv

  private val realPrefix: UIO[String] = ZIO.succeed(s"test/${UUID.randomUUID()}")

  def spec = suite("S3ObjectStore")(
    suite("Floci")(common(ZIO.succeed("files")) *).provideShared(Floci.objectStore),
    suite("real S3")((common(realPrefix) ++ signatureChecks(realPrefix)) *).provideShared(realS3)
      @@ TestAspect.ifEnvSet("S3_INTEGRATION") @@ TestAspect.withLiveSystem @@ TestAspect.withLiveRandom,
  ) @@ TestAspect.withLiveClock @@ TestAspect.timeout(2.minutes)
