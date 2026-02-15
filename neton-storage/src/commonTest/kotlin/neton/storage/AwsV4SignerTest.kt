package neton.storage

import neton.storage.s3.AwsV4Signer
import neton.storage.s3.S3Utils
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AwsV4SignerTest {

    @Test
    fun `hexEncode works`() {
        assertEquals("", AwsV4Signer.hexEncode(ByteArray(0)))
        assertEquals("00", AwsV4Signer.hexEncode(byteArrayOf(0)))
        assertEquals("ff", AwsV4Signer.hexEncode(byteArrayOf(-1)))
        assertEquals("0102ff", AwsV4Signer.hexEncode(byteArrayOf(1, 2, -1)))
    }

    @Test
    fun `sha256 empty payload`() {
        // SHA256 of empty input is a well-known constant
        val hash = AwsV4Signer.hexEncode(AwsV4Signer.sha256(ByteArray(0)))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hash)
    }

    @Test
    fun `hmacSha256 produces 32 bytes`() {
        val result = AwsV4Signer.hmacSha256("key".encodeToByteArray(), "data".encodeToByteArray())
        assertEquals(32, result.size)
    }

    @Test
    fun `formatAmzDate correct for known epoch`() {
        // 2026-02-14T09:30:00Z = epoch millis 1771058200000 (approximately)
        // Let's use a simpler known value: 2020-01-01T00:00:00Z = 1577836800000
        val date = AwsV4Signer.formatAmzDate(1577836800000L)
        assertEquals("20200101T000000Z", date)
    }

    @Test
    fun `formatAmzDate handles epoch zero`() {
        val date = AwsV4Signer.formatAmzDate(0L)
        assertEquals("19700101T000000Z", date)
    }

    @Test
    fun `percentEncodePath preserves slashes`() {
        assertEquals("path/to/file.jpg", AwsV4Signer.percentEncodePath("path/to/file.jpg"))
        assertEquals("path/to/my%20file.jpg", AwsV4Signer.percentEncodePath("path/to/my file.jpg"))
    }

    @Test
    fun `percentEncodeValue encodes special chars`() {
        assertEquals("hello%20world", AwsV4Signer.percentEncodeValue("hello world"))
        assertEquals("foo%2Fbar", AwsV4Signer.percentEncodeValue("foo/bar"))
        assertEquals("a-b_c.d~e", AwsV4Signer.percentEncodeValue("a-b_c.d~e"))
    }

    @Test
    fun `sign produces authorization header`() {
        val result = AwsV4Signer.sign(
            method = "GET",
            url = "https://mybucket.s3.amazonaws.com/test.txt",
            headers = mapOf("Host" to "mybucket.s3.amazonaws.com"),
            payload = ByteArray(0),
            accessKey = "AKIAIOSFODNN7EXAMPLE",
            secretKey = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
            region = "us-east-1"
        )
        assertTrue(result.containsKey("Authorization"))
        assertTrue(result["Authorization"]!!.startsWith("AWS4-HMAC-SHA256"))
        assertTrue(result.containsKey("x-amz-date"))
        assertTrue(result.containsKey("x-amz-content-sha256"))
    }

    // --- S3Utils tests ---

    @Test
    fun `parseEndpoint extracts parts`() {
        val parts = S3Utils.parseEndpoint("https://s3.amazonaws.com")
        assertEquals("https", parts.scheme)
        assertEquals("s3.amazonaws.com", parts.host)
        assertEquals(443, parts.port)

        val parts2 = S3Utils.parseEndpoint("http://127.0.0.1:9000")
        assertEquals("http", parts2.scheme)
        assertEquals("127.0.0.1", parts2.host)
        assertEquals(9000, parts2.port)
    }

    @Test
    fun `buildS3Url pathStyle true`() {
        val url = S3Utils.buildS3Url("http://127.0.0.1:9000", "mybucket", "path/to/file.jpg", true)
        assertEquals("http://127.0.0.1:9000/mybucket/path/to/file.jpg", url)
    }

    @Test
    fun `buildS3Url pathStyle false`() {
        val url = S3Utils.buildS3Url("https://s3.amazonaws.com", "mybucket", "path/to/file.jpg", false)
        assertEquals("https://mybucket.s3.amazonaws.com/path/to/file.jpg", url)
    }

    @Test
    fun `buildHostHeader pathStyle true`() {
        assertEquals("127.0.0.1:9000", S3Utils.buildHostHeader("http://127.0.0.1:9000", "mybucket", true))
    }

    @Test
    fun `buildHostHeader pathStyle false`() {
        assertEquals(
            "mybucket.s3.amazonaws.com",
            S3Utils.buildHostHeader("https://s3.amazonaws.com", "mybucket", false)
        )
    }

    @Test
    fun `parseListObjectsV2Response parses contents`() {
        val xml = """
            <ListBucketResult>
                <IsTruncated>false</IsTruncated>
                <Contents>
                    <Key>file1.txt</Key>
                    <Size>1024</Size>
                    <LastModified>2026-02-14T09:30:00.000Z</LastModified>
                </Contents>
                <Contents>
                    <Key>file2.txt</Key>
                    <Size>2048</Size>
                    <LastModified>2026-02-14T10:00:00.000Z</LastModified>
                </Contents>
                <CommonPrefixes>
                    <Prefix>subdir/</Prefix>
                </CommonPrefixes>
            </ListBucketResult>
        """.trimIndent()

        val result = S3Utils.parseListObjectsV2Response(xml)
        assertEquals(2, result.contents.size)
        assertEquals("file1.txt", result.contents[0].key)
        assertEquals(1024, result.contents[0].size)
        assertEquals(1, result.commonPrefixes.size)
        assertEquals("subdir/", result.commonPrefixes[0])
        assertEquals(false, result.isTruncated)
    }

    @Test
    fun `parseIso8601ToEpochMillis parses correctly`() {
        // 2020-01-01T00:00:00Z = 1577836800000
        assertEquals(1577836800000L, S3Utils.parseIso8601ToEpochMillis("2020-01-01T00:00:00.000Z"))
        // 1970-01-01T00:00:00Z = 0
        assertEquals(0L, S3Utils.parseIso8601ToEpochMillis("1970-01-01T00:00:00.000Z"))
    }
}
