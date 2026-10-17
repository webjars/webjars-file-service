import zio.*
import zio.http.*

import java.io.IOException

/**
 * A minimal S3 client over zio-http, covering exactly what the service
 * needs: GET (streamed straight through to the HTTP response), PUT (the byte
 * array is handed to Netty without copying) and, for tests/emulators,
 * CreateBucket.
 *
 * Requests are signed with SigV4 using `UNSIGNED-PAYLOAD`, so bodies are never
 * hashed; S3 traffic goes over TLS. S3's REST API only speaks XML (no CBOR
 * protocol), and these operations have raw-byte bodies, so the only XML ever
 * parsed is the `<Code>` of an error response.
 *
 * Transient failures (connection errors, 500, 503 SlowDown) are retried with
 * backoff; GETs only before any body bytes are handed to the caller.
 */
final case class S3ObjectStore(
  client: Client,
  config: S3ObjectStore.Config,
  signingKeys: Ref[Option[SigV4.SigningKey]],
  writePermits: Semaphore,
) extends ObjectStore:
  import S3ObjectStore.*

  def get(key: StoreKey): ZIO[Scope, Throwable, Option[StoredObject]] =
    ZIO.scopeWith: outer =>
      outer.fork.flatMap: connection =>
        signed(Method.GET, objectUrl(key), Headers.empty, Body.empty, SigV4.emptyPayloadHash)
          .flatMap(req => connection.extend(client.request(req)))
          .flatMap: resp =>
            resp.status match
              case Status.Ok =>
                ZIO.some(StoredObject(resp.header(Header.ContentLength).map(_.length), resp.body.asStream))
              case Status.NotFound =>
                connection.close(Exit.unit).as(None)
              case _ =>
                errorFrom("GetObject", key, resp).flatMap(e => connection.close(Exit.unit) *> ZIO.fail(e))
          .onError(_ => connection.close(Exit.unit))
    .retry(transientRetry)

  def write(key: StoreKey, bytes: Array[Byte], contentType: Option[MediaType]): Task[Unit] =
    writePermits.withPermit:
      signed(Method.PUT, objectUrl(key), Headers(contentType.map(Header.ContentType(_)).toSeq), Body.fromArray(bytes), SigV4.unsignedPayload)
        .flatMap(client.batched(_))
        .flatMap(expectOk("PutObject", key, _))
        .retry(transientRetry)

  /** For emulators and tests; production buckets come from CloudFormation. */
  def createBucket: Task[Unit] =
    signed(Method.PUT, bucketUrl, Headers.empty, Body.empty, SigV4.emptyPayloadHash)
      .flatMap(client.batched(_))
      .flatMap(expectOk("CreateBucket", StoreKey(""), _))

  private def expectOk(operation: String, key: StoreKey, resp: Response): Task[Unit] =
    if resp.status.isSuccess then ZIO.unit else errorFrom(operation, key, resp).flip

  private def errorFrom(operation: String, key: StoreKey, resp: Response): UIO[S3Error] =
    resp.body.asString.orElseSucceed("").map: body =>
      S3Error(operation, config.bucket, key, resp.status.code, errorCode.findFirstMatchIn(body).map(_.group(1)))

  private def objectUrl(key: StoreKey): URL =
    config.bucketUrl.path(key.value.split('/').iterator.filter(_.nonEmpty).foldLeft(config.bucketUrl.path)(_ / _))

  private def bucketUrl: URL = config.bucketUrl

  private def signed(method: Method, url: URL, extra: Headers, body: Body, payloadHash: String): UIO[Request] =
    Clock.instant.flatMap: now =>
      val amzDate = SigV4.amzDate(now)
      signingKey(amzDate.take(8)).map: key =>
        val host = url.hostPort.getOrElse("")
        val amzHeaders =
          List("x-amz-content-sha256" -> payloadHash, "x-amz-date" -> amzDate) ++
            config.credentials.sessionToken.map("x-amz-security-token" -> _)
        val canonicalUri = SigV4.canonicalUri(url.path.segments)
        val authorization = SigV4.authorization(
          config.credentials.accessKeyId, key, method.name, canonicalUri, "", ("host" -> host) :: amzHeaders, payloadHash, amzDate,
        )
        Request(
          method = method,
          url = url,
          headers = Headers(Header.Custom("Host", host) :: Header.Custom("Authorization", authorization) :: amzHeaders.map(Header.Custom(_, _))) ++ extra,
          body = body,
        )

  private def signingKey(date: String): UIO[SigV4.SigningKey] =
    signingKeys.modify:
      case Some(k) if k.date == date => (k, Some(k))
      case _ =>
        val k = SigV4.signingKey(config.credentials.secretAccessKey, date, config.region)
        (k, Some(k))

object S3ObjectStore:

  enum Addressing:
    /** `https://<bucket>.s3.<region>.amazonaws.com/<key>` */
    case AwsVirtualHosted
    /** `<endpoint>/<bucket>/<key>`, for S3-compatible emulators. */
    case PathStyle(endpoint: URL)

  final case class Config(bucket: BucketName, region: AwsRegion, addressing: Addressing, credentials: AwsCredentials):
    val bucketUrl: URL =
      addressing match
        case Addressing.AwsVirtualHosted =>
          URL(Path.root, URL.Location.Absolute(Scheme.HTTPS, s"${bucket.value}.s3.${region.value}.amazonaws.com", None))
        case Addressing.PathStyle(endpoint) =>
          endpoint.path(Path.root / bucket.value)

  final case class S3Error(operation: String, bucket: BucketName, key: StoreKey, status: Int, code: Option[String])
    extends IOException(s"S3 $operation s3://${bucket.value}/${key.value} failed: $status ${code.getOrElse("")}")

  private val errorCode = "<Code>([^<]+)</Code>".r

  private val transientRetry: Schedule[Any, Throwable, Any] =
    (Schedule.exponential(100.millis).jittered && Schedule.recurs(3)).whileInput[Throwable]:
      case e: S3Error => e.status == 500 || e.status == 503
      case _          => true // connection-level failures

  /**
   * PUTs in flight; a transport-level backstop (WebJarStore limits file
   * uploads to the same number), keeping most of the pool for reads.
   */
  private val maxConcurrentWrites = 128

  private val clientConfig: ZClient.Config =
    ZClient.Config.default
      .dynamicConnectionPool(minimum = 4, maximum = 512, ttl = 60.seconds)
      .addUserAgentHeader(false)
      .connectionTimeout(10.seconds)

  def make(client: Client, config: Config): UIO[S3ObjectStore] =
    for
      keys    <- Ref.make(Option.empty[SigV4.SigningKey])
      permits <- Semaphore.make(maxConcurrentWrites.toLong)
    yield S3ObjectStore(client, config, keys, permits)

  /** With its own connection pool, so S3 traffic doesn't share the Maven Central client (or its request logging). */
  def scoped(config: Config): ZIO[Scope, Throwable, S3ObjectStore] =
    (ZLayer.succeed(clientConfig) >>> Client.fromConfig).build.flatMap(env => make(env.get[Client], config))

  /**
   * Production: `S3_BUCKET`, `AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY`
   * are required (`AWS_SESSION_TOKEN` optional); `AWS_REGION` defaults to
   * us-east-1, the same region as Heroku's US dynos, so S3 → dyno transfer is
   * free.
   */
  val fromEnv: ZLayer[Any, Throwable, ObjectStore] =
    ZLayer.scoped:
      def required(name: String) = System.env(name).someOrFail(RuntimeException(s"$name env var not set"))
      for
        bucket <- required("S3_BUCKET")
        keyId  <- required("AWS_ACCESS_KEY_ID")
        secret <- required("AWS_SECRET_ACCESS_KEY")
        token  <- System.env("AWS_SESSION_TOKEN")
        region <- System.env("AWS_REGION").map(_.fold(AwsRegion.usEast1)(AwsRegion(_)))
        store  <- scoped(Config(BucketName(bucket), region, Addressing.AwsVirtualHosted, AwsCredentials(keyId, secret, token)))
      yield store
