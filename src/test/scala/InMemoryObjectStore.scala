import zio.*
import zio.http.MediaType
import zio.stream.ZStream

/** Fake [[ObjectStore]] for fast tests; the Floci/S3 specs cover the real client. */
final case class InMemoryObjectStore(objects: Ref[Map[StoreKey, Chunk[Byte]]]) extends ObjectStore:
  def get(key: StoreKey): ZIO[Scope, Throwable, Option[StoredObject]] =
    objects.get.map(_.get(key).map(bytes => StoredObject(Some(bytes.length.toLong), ZStream.fromChunk(bytes))))

  def write(key: StoreKey, bytes: Array[Byte], contentType: Option[MediaType]): Task[Unit] =
    objects.update(_.updated(key, Chunk.fromArray(bytes)))

object InMemoryObjectStore:
  val make: UIO[InMemoryObjectStore] = Ref.make(Map.empty[StoreKey, Chunk[Byte]]).map(InMemoryObjectStore(_))
  val layer: ULayer[ObjectStore] = ZLayer.fromZIO(make)
