import zio.*
import zio.http.MediaType
import zio.stream.ZStream

opaque type StoreKey = String

object StoreKey:
  def apply(s: String): StoreKey = s
  extension (k: StoreKey) def value: String = k

opaque type BucketName = String

object BucketName:
  def apply(s: String): BucketName = s
  extension (b: BucketName) def value: String = b

/**
 * An object's body. `body` can only be consumed while the [[Scope]] that
 * [[ObjectStore.get]] ran in is open (for S3 it is the HTTP connection).
 */
final case class StoredObject(contentLength: Option[Long], body: ZStream[Any, Throwable, Byte])

/** Durable storage for extracted webjar files. Objects are immutable once written. */
trait ObjectStore:
  /** The object, or `None` if it doesn't exist, in one round trip. */
  def get(key: StoreKey): ZIO[Scope, Throwable, Option[StoredObject]]

  /**
   * `bytes` is handed to the transport without copying, so the caller must
   * not modify it afterwards.
   */
  def write(key: StoreKey, bytes: Array[Byte], contentType: Option[MediaType]): Task[Unit]

  /** Whole object, or `None` if it doesn't exist. For small objects (manifests). */
  def read(key: StoreKey): Task[Option[Chunk[Byte]]] =
    ZIO.scoped(get(key).flatMap(ZIO.foreach(_)(_.body.runCollect)))
