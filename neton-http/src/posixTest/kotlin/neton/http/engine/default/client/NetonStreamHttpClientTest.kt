package neton.http.engine.default.client

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import neton.core.http.HttpHeaders
import neton.http.client.HttpClient
import neton.http.client.HttpClientConfig
import neton.http.client.HttpClientError
import neton.http.client.HttpClientException
import neton.http.client.HttpClientMethod
import neton.http.client.HttpClientRequest
import neton.http.client.HttpClientStreamChunk
import neton.http.engine.default.TestIdentity
import neton.http.engine.default.clientTls
import neton.http.engine.default.testIdentity
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** The built-in client beyond the shared suite: TLS + ALPN, the CONNECT proxy, Host rules, the per-host limit. */
class NetonStreamHttpClientTest {

    private val identity: TestIdentity by lazy { testIdentity() }

    private fun client(alpn: List<String> = listOf("h2", "http/1.1"), block: HttpClientConfig.() -> Unit = {}): HttpClient =
        HttpClient.createWith({ NetonStreamHttpClient(it, clientTls(identity, alpn)) }, block)

    private fun get(url: String, headers: HttpHeaders = HttpHeaders.EMPTY) =
        HttpClientRequest(method = HttpClientMethod.Get, url = url, headers = headers)

    @Test
    fun http1OverTlsSendsTheUrlAuthorityAsHost() = runBlocking {
        val origin = TestOrigin.start(identity, alpn = listOf("http/1.1"))
        val client = client()
        try {
            val response = withTimeout(20_000) {
                client.request(get("https://127.0.0.1:${origin.port}/a?b=1", HttpHeaders.of("Host" to "spoofed.example")))
            }
            assertEquals(200, response.statusCode)
            val seen = origin.seen.receive()
            assertEquals("HTTP/1.1", seen.version)
            assertEquals(listOf("127.0.0.1:${origin.port}"), seen.all("host"))
            assertEquals("/a?b=1", seen.path)
        } finally {
            client.close()
            origin.stop()
        }
    }

    @Test
    fun streamOverHttp2EmitsStartBytesEnd() = runBlocking {
        val origin = TestOrigin.start(identity) { 200 to "data: hello\n\n".encodeToByteArray() }
        val client = client()
        try {
            val chunks = withTimeout(20_000) {
                client.stream(HttpClientRequest(HttpClientMethod.Post, "https://127.0.0.1:${origin.port}/sse")).toList()
            }
            assertEquals(200, (chunks.first() as HttpClientStreamChunk.Start).statusCode)
            assertTrue(chunks.last() is HttpClientStreamChunk.End)
            val body = chunks.filterIsInstance<HttpClientStreamChunk.Bytes>().fold(ByteArray(0)) { a, c -> a + c.bytes }
            assertEquals("data: hello\n\n", body.decodeToString())
            assertEquals("HTTP/2.0", origin.seen.receive().version)
        } finally {
            client.close()
            origin.stop()
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun httpsThroughProxyUsesAConnectTunnel() = runBlocking {
        val origin = TestOrigin.start(identity) { 200 to "tunnelled".encodeToByteArray() }
        val proxy = ConnectProxy.start()
        val client = client { proxyUrl = "http://user:p%40ss@127.0.0.1:${proxy.port}" }
        try {
            val response = withTimeout(20_000) { client.request(get("https://127.0.0.1:${origin.port}/through")) }
            assertEquals("tunnelled", response.body)
            val head = proxy.heads.receive()
            assertTrue(head.startsWith("CONNECT 127.0.0.1:${origin.port} HTTP/1.1\r\n"), "proxy saw: $head")
            val expected = "Basic " + Base64.encode("user:p@ss".encodeToByteArray())
            assertTrue("Proxy-Authorization: $expected\r\n" in head, "proxy credentials missing: $head")
            // TLS end to end through the tunnel: ALPN still reaches the origin.
            val seen = origin.seen.receive()
            assertEquals("HTTP/2.0", seen.version)
            assertTrue(seen.all("host").isEmpty())
            assertTrue(seen.all("proxy-authorization").isEmpty(), "proxy credentials leaked to the origin")
        } finally {
            client.close()
            proxy.stop()
            origin.stop()
        }
    }

    @Test
    fun proxyRefusingConnectIsANetworkError() = runBlocking {
        // A plain origin that answers CONNECT with 405 plays a proxy that refuses.
        val notAProxy = TestOrigin.start(null) { 405 to ByteArray(0) }
        val client = client { proxyUrl = "http://127.0.0.1:${notAProxy.port}" }
        try {
            val error = expectFailure { client.request(get("https://example.invalid/")) }
            assertTrue(error is HttpClientError.Network && "CONNECT" in error.message, "got $error")
        } finally {
            client.close()
            notAProxy.stop()
        }
    }

    @Test
    fun untrustedCertificateIsANetworkError() = runBlocking {
        val origin = TestOrigin.start(identity)
        // The system trust store, which does not know the test CA.
        val client = HttpClient.createWith({ NetonStreamHttpClient(it) })
        try {
            val error = expectFailure { client.request(get("https://127.0.0.1:${origin.port}/")) }
            assertTrue(error is HttpClientError.Network, "got $error")
        } finally {
            client.close()
            origin.stop()
        }
    }

    @Test
    fun maxConnectionsPerHostRejectsInsteadOfQueueing() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val origin = TestOrigin.start(null) { seen ->
            if (seen.path == "/slow") release.await()
            200 to seen.path.encodeToByteArray()
        }
        val client = HttpClient.createWith({ NetonStreamHttpClient(it) }) { maxConnectionsPerHost = 1 }
        try {
            val base = "http://127.0.0.1:${origin.port}"
            val slow = async { client.request(get("$base/slow")) }
            origin.seen.receive() // the slow request holds the only connection
            val error = expectFailure { client.request(get("$base/second")) }
            assertTrue(error is HttpClientError.Network && "maxConnectionsPerHost" in error.message, "got $error")
            release.complete(Unit)
            assertEquals("/slow", slow.await().body)
            // The connection went back to the pool: the next request reuses it.
            assertEquals("/third", client.request(get("$base/third")).body)
        } finally {
            client.close()
            origin.stop()
        }
    }

    @Test
    fun concurrentCallersOnManyThreadsShareOneHttp2Connection() = runBlocking {
        val origin = TestOrigin.start(identity) { seen -> 200 to seen.path.encodeToByteArray() }
        val client = client()
        try {
            val bodies = withContext(Dispatchers.Default) {
                (1..64).map { i -> async { client.request(get("https://127.0.0.1:${origin.port}/$i")).body } }.awaitAll()
            }
            assertEquals((1..64).map { "/$it" }, bodies)
        } finally {
            client.close()
            origin.stop()
        }
    }

    @Test
    fun closeDuringAStreamFailsTheCollectorInsteadOfHangingIt() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val origin = TestOrigin.start(null) { gate.await(); 200 to ByteArray(0) }
        val client = HttpClient.createWith({ NetonStreamHttpClient(it) })
        try {
            val collecting = async { runCatching { client.stream(get("http://127.0.0.1:${origin.port}/hang")).toList() } }
            origin.seen.receive()
            client.close()
            val result = withTimeout(10_000) { collecting.await() }
            val e = result.exceptionOrNull() as? HttpClientException ?: fail("expected HttpClientException, got $result")
            assertTrue("closed" in e.error.message, "got ${e.error}")
        } finally {
            gate.complete(Unit)
            origin.stop()
        }
    }

    private suspend fun expectFailure(block: suspend () -> Unit): HttpClientError {
        try {
            withTimeout(20_000) { block() }
        } catch (e: HttpClientException) {
            return e.error
        }
        fail("expected HttpClientException")
    }
}
