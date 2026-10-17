import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.model.S3Exception
import zio.*
import zio.s3.{S3, UploadOptions}
import zio.stream.ZStream


opaque type StoreKey = String

object StoreKey:
  def apply(s: String): StoreKey = s
  extension (k: StoreKey) def value: String = k

opaque type BucketName = String

object BucketName:
  def apply(s: String): BucketName = s
  extension (b: BucketName) def value: String = b

/** Durable storage for extracted webjar files. Objects are immutable once written. */
trait ObjectStore:
  /** Content length, or `None` if the object doesn't exist. */
  def size(key: StoreKey): Task[Option[Long]]
  def stream(key: StoreKey): ZStream[Any, Throwable, Byte]
  /** Whole object, or `None` if it doesn't exist. For small objects (manifests). */
  def read(key: StoreKey): Task[Option[Chunk[Byte]]]
  def write(key: StoreKey, bytes: Chunk[Byte], contentType: Option[String]): Task[Unit]

final case class S3ObjectStore(s3: S3, bucket: BucketName) extends ObjectStore:

  private def notFoundAsNone[A](effect: IO[S3Exception, A]): Task[Option[A]] =
    effect.map(Some(_)).catchSome:
      case e: S3Exception if e.statusCode() == 404 => ZIO.none

  def size(key: StoreKey): Task[Option[Long]] =
    notFoundAsNone(s3.getObjectMetadata(bucket.value, key.value)).map(_.map(_.contentLength))

  def stream(key: StoreKey): ZStream[Any, Throwable, Byte] =
    s3.getObject(bucket.value, key.value)

  def read(key: StoreKey): Task[Option[Chunk[Byte]]] =
    notFoundAsNone(s3.getObject(bucket.value, key.value).runCollect)

  def write(key: StoreKey, bytes: Chunk[Byte], contentType: Option[String]): Task[Unit] =
    s3.putObject(
      bucket.value,
      key.value,
      bytes.length.toLong,
      ZStream.fromChunk(bytes),
      UploadOptions.default.copy(contentType = contentType),
    )

object S3ObjectStore:

  /**
   * Production: `S3_BUCKET` and AWS credentials (`AWS_ACCESS_KEY_ID` /
   * `AWS_SECRET_ACCESS_KEY`) are required; `AWS_REGION` defaults to
   * us-east-1, the same region as Heroku's US dynos, so S3 → dyno transfer
   * is free.
   */
  val fromEnv: ZLayer[Any, Throwable, ObjectStore] =
    ZLayer.scoped:
      for
        bucket <- System.env("S3_BUCKET").someOrFail(RuntimeException("S3_BUCKET env var not set"))
        region <- System.env("AWS_REGION").map(_.fold(Region.US_EAST_1)(Region.of(_).nn))
        s3     <- zio.s3.liveZIO(region, zio.s3.providers.env).build.map(_.get[S3])
      yield S3ObjectStore(s3, BucketName(bucket))
