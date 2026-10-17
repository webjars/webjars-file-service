import zio.*
import zio.stream.ZStream

/** Fake [[ObjectStore]] for fast tests; the Floci-backed spec covers the real S3 client. */
final case class InMemoryObjectStore(objects: Ref[Map[StoreKey, Chunk[Byte]]]) extends ObjectStore:
  def size(key: StoreKey): Task[Option[Long]] =
    objects.get.map(_.get(key).map(_.length.toLong))

  def stream(key: StoreKey): ZStream[Any, Throwable, Byte] =
    ZStream.fromZIO(read(key).someOrFail(java.nio.file.NoSuchFileException(key.value))).flattenChunks

  def read(key: StoreKey): Task[Option[Chunk[Byte]]] =
    objects.get.map(_.get(key))

  def write(key: StoreKey, bytes: Chunk[Byte], contentType: Option[String]): Task[Unit] =
    objects.update(_.updated(key, bytes))

object InMemoryObjectStore:
  val layer: ULayer[ObjectStore & InMemoryObjectStore] =
    ZLayer.fromZIO(Ref.make(Map.empty[StoreKey, Chunk[Byte]]).map(InMemoryObjectStore(_)))
