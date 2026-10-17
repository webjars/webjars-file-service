import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.{Instant, ZoneOffset}
import java.time.format.DateTimeFormatter
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

final case class AwsCredentials(accessKeyId: String, secretAccessKey: String, sessionToken: Option[String])

opaque type AwsRegion = String

object AwsRegion:
  def apply(s: String): AwsRegion = s
  val usEast1: AwsRegion = "us-east-1"
  extension (r: AwsRegion) def value: String = r

/**
 * AWS Signature Version 4 for S3 (header-based).
 * https://docs.aws.amazon.com/AmazonS3/latest/API/sig-v4-header-based-auth.html
 */
object SigV4:

  /** Body hash for requests without a body. */
  val emptyPayloadHash: String = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

  /** Skips hashing the body; integrity comes from TLS (and S3's own checks). */
  val unsignedPayload: String = "UNSIGNED-PAYLOAD"

  private val service = "s3"
  private val hex     = HexFormat.of()

  private val amzDateFormat   = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

  def amzDate(instant: Instant): String = amzDateFormat.format(instant)

  /**
   * AWS's `UriEncode` of each path segment (RFC 3986 unreserved characters
   * kept, everything else percent-encoded as UTF-8), joined with `/`. S3
   * decodes the request path and re-encodes it this way, so this must not
   * depend on how the HTTP client happens to encode the path on the wire.
   */
  def canonicalUri(segments: Seq[String]): String =
    val sb = java.lang.StringBuilder()
    segments.foreach: segment =>
      sb.append('/')
      segment.getBytes(UTF_8).foreach: b =>
        val c = (b & 0xff).toChar
        if (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.' || c == '~' then
          sb.append(c)
        else
          sb.append('%').append(hexUpper((b >> 4) & 0xf)).append(hexUpper(b & 0xf))
    if segments.isEmpty then "/" else sb.toString

  private def hexUpper(nibble: Int): Char = "0123456789ABCDEF".charAt(nibble)

  /** `kSigning` from the spec. Depends only on the date, so callers can cache it per day. */
  final case class SigningKey(date: String, region: AwsRegion, key: Array[Byte])

  def signingKey(secretAccessKey: String, date: String, region: AwsRegion): SigningKey =
    val kDate    = hmac(s"AWS4$secretAccessKey".getBytes(UTF_8), date)
    val kRegion  = hmac(kDate, region.value)
    val kService = hmac(kRegion, service)
    SigningKey(date, region, hmac(kService, "aws4_request"))

  /**
   * The `Authorization` header value.
   *
   * @param canonicalUri see [[canonicalUri]]
   * @param headers      every header to sign, including `host`, `x-amz-date`
   *                     and `x-amz-content-sha256`; names in any case
   */
  def authorization(
    accessKeyId: String,
    key: SigningKey,
    method: String,
    canonicalUri: String,
    canonicalQuery: String,
    headers: Seq[(String, String)],
    payloadHash: String,
    amzDate: String,
  ): String =
    val sorted        = headers.map((n, v) => (n.toLowerCase(java.util.Locale.ROOT).nn, v.trim.nn)).sortBy(_._1)
    val signedHeaders = sorted.map(_._1).mkString(";")
    val canonicalRequest =
      s"$method\n$canonicalUri\n$canonicalQuery\n${sorted.map((n, v) => s"$n:$v\n").mkString}\n$signedHeaders\n$payloadHash"
    val scope        = s"${key.date}/${key.region.value}/$service/aws4_request"
    val stringToSign = s"AWS4-HMAC-SHA256\n$amzDate\n$scope\n${sha256Hex(canonicalRequest)}"
    val signature    = hex.formatHex(hmac(key.key, stringToSign))
    s"AWS4-HMAC-SHA256 Credential=$accessKeyId/$scope, SignedHeaders=$signedHeaders, Signature=$signature"

  private def sha256Hex(s: String): String =
    hex.formatHex(MessageDigest.getInstance("SHA-256").nn.digest(s.getBytes(UTF_8)))

  private def hmac(key: Array[Byte], data: String): Array[Byte] =
    val mac = Mac.getInstance("HmacSHA256").nn
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    mac.doFinal(data.getBytes(UTF_8)).nn
