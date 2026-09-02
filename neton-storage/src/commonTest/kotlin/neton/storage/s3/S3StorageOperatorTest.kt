package neton.storage.s3

import kotlinx.coroutines.test.runTest
import neton.core.http.HttpHeaders
import neton.http.client.HttpClientBody
import neton.http.client.HttpClientError
import neton.http.client.HttpClientException
import neton.http.client.HttpClientMethod
import neton.http.client.HttpClientResponse
import neton.http.client.HttpClientStreamChunk
import neton.http.testkit.ScriptedHttpClient
import neton.storage.StorageNotFoundException
import neton.storage.WriteOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * S3 through the contract-layer client, with no engine on the test classpath.
 * The transport swap is the whole change here, so these pin what crosses it:
 * bytes, signing headers, status mapping, and who owns the client.
 */
class S3StorageOperatorTest {

    private fun operator(http: ScriptedHttpClient) = S3StorageOperator(
        name = "s3", endpoint = "https://s3.example.test", region = "us-east-1", bucket = "bkt",
        accessKey = "AKIA_TEST", secretKey = "secret", pathStyle = true, httpClient = http, logger = null,
    )

    @Test
    fun writeSendsASignedPutWithTheBytesVerbatim() = runTest {
        val http = ScriptedHttpClient().on(HttpClientMethod.Put, "https://s3.example.test/bkt/") { HttpClientResponse(200, HttpHeaders.EMPTY, "") }
        val payload = ByteArray(64) { (it * 5).toByte() }.also { it[0] = 0; it[1] = 0xFF.toByte() }

        operator(http).write("dir/obj.bin", payload, WriteOptions(contentType = "application/octet-stream"))

        val sent = http.recorded.single()
        assertEquals("https://s3.example.test/bkt/dir/obj.bin", sent.url)
        assertEquals("s3.example.test", sent.headers.get("Host"))
        assertNotNull(sent.headers.get("Authorization"), "SigV4 signature must be attached")
        assertNotNull(sent.headers.get("x-amz-date"))
        val body = sent.body as HttpClientBody.Bytes
        assertTrue(body.bytes.contentEquals(payload), "object bytes changed in transit")
    }

    @Test
    fun readReturnsObjectBytesWithoutTextDecoding() = runTest {
        // Every byte value once: a String round-trip would corrupt most of them.
        val payload = ByteArray(256) { it.toByte() }
        val http = ScriptedHttpClient().onStream(HttpClientMethod.Get, "https://s3.example.test/bkt/") {
            listOf(
                HttpClientStreamChunk.Bytes(payload.copyOfRange(0, 100)),
                HttpClientStreamChunk.Bytes(payload.copyOfRange(100, 256)),
                HttpClientStreamChunk.End(HttpHeaders.EMPTY),
            )
        }

        val got = operator(http).read("blob")
        assertTrue(got.contentEquals(payload))
    }

    @Test
    fun readMaps404ToNotFound() = runTest {
        val http = ScriptedHttpClient().onStream(HttpClientMethod.Get, "https://s3.example.test/bkt/") {
            throw HttpClientException(HttpClientError.Http(404, "HTTP 404", "<Error/>"))
        }
        assertFailsWith<StorageNotFoundException> { operator(http).read("missing") }
    }

    @Test
    fun existsUsesHeadAndReadsTheStatus() = runTest {
        val http = ScriptedHttpClient()
            .on(HttpClientMethod.Head, "https://s3.example.test/bkt/there") { HttpClientResponse(200, HttpHeaders.of("Content-Length" to "3"), "") }
            .on(HttpClientMethod.Head, "https://s3.example.test/bkt/gone") { HttpClientResponse(404, HttpHeaders.EMPTY, "") }
        val op = operator(http)
        assertTrue(op.exists("there"))
        assertFalse(op.exists("gone"))
        assertEquals(3L, op.stat("there").size)
    }

    @Test
    fun closeDoesNotCloseTheBorrowedClient() = runTest {
        val http = ScriptedHttpClient()
        operator(http).close()
        // The application owns the client; storage tearing it down would take
        // every other user of it (neton-ai, webhooks) down with it.
        assertFalse(http.isClosed)
    }
}
