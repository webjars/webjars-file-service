import zio.test.*

import java.time.Instant

object SigV4Spec extends ZIOSpecDefault:

  // "Example: GET Object" from
  // https://docs.aws.amazon.com/AmazonS3/latest/API/sig-v4-header-based-auth.html
  private val secret  = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
  private val amzDate = "20130524T000000Z"

  def spec = suite("SigV4")(
    test("matches AWS's documented GET Object example"):
      val key = SigV4.signingKey(secret, "20130524", AwsRegion.usEast1)
      val auth = SigV4.authorization(
        accessKeyId    = "AKIAIOSFODNN7EXAMPLE",
        key            = key,
        method         = "GET",
        canonicalUri   = "/test.txt",
        canonicalQuery = "",
        headers        = List(
          "Host"                 -> "examplebucket.s3.amazonaws.com",
          "Range"                -> "bytes=0-9",
          "x-amz-content-sha256" -> SigV4.emptyPayloadHash,
          "x-amz-date"           -> amzDate,
        ),
        payloadHash    = SigV4.emptyPayloadHash,
        amzDate        = amzDate,
      )
      assertTrue(
        auth == "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request, " +
          "SignedHeaders=host;range;x-amz-content-sha256;x-amz-date, " +
          "Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41"
      )
    ,
    test("canonicalUri percent-encodes everything but RFC 3986 unreserved characters"):
      assertTrue(
        SigV4.canonicalUri(List("bucket", "a b+c", "reserved!*'()@:$&,;=~.js", "ünï", "100%")) ==
          "/bucket/a%20b%2Bc/reserved%21%2A%27%28%29%40%3A%24%26%2C%3B%3D~.js/%C3%BCn%C3%AF/100%25",
        SigV4.canonicalUri(Nil) == "/",
      )
    ,
    test("amzDate is the basic ISO-8601 UTC form"):
      assertTrue(SigV4.amzDate(Instant.parse("2013-05-24T00:00:00Z")) == amzDate)
    ,
  )
