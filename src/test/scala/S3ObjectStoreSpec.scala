import zio.*
import zio.test.*

/** The real S3 client against Floci. */
object S3ObjectStoreSpec extends ZIOSpecDefault:

  private val key = StoreKey("files/org.webjars/demo/1.0.0/demo/1.0.0/demo.js")
  private val bytes = Chunk.fromArray("console.log('hi')".getBytes)

  def spec = suite("S3ObjectStore")(
    test("write then read, size, and stream"):
      for
        store  <- ZIO.service[ObjectStore]
        _      <- store.write(key, bytes, Some("text/javascript"))
        read   <- store.read(key)
        size   <- store.size(key)
        stream <- store.stream(key).runCollect
      yield assertTrue(read.contains(bytes), size.contains(bytes.length.toLong), stream == bytes)
    ,
    test("missing objects are None, not errors"):
      for
        store <- ZIO.service[ObjectStore]
        read  <- store.read(StoreKey("files/missing"))
        size  <- store.size(StoreKey("files/missing"))
      yield assertTrue(read.isEmpty, size.isEmpty)
    ,
  ).provideShared(Floci.objectStore) @@ TestAspect.timeout(2.minutes)
