@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package neton.http.netonstream

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import neton.core.http.Cookie
import neton.core.http.adapter.HttpServerConfig
import neton.core.http.adapter.TlsSettings
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/** The adapter over real sockets: HTTP/1.1, h2c and TLS on the port it serves. */
@OptIn(ExperimentalAtomicApi::class)
class NetonStreamServerTest {

    private val json = (1..60).joinToString(",", "[", "]") { """{"id":$it,"name":"item-$it"}""" }

    private fun basicRoutes() = listOf(
        get("/hello") { ctx -> ctx.response.text("hello"); null },
        get("/n") { ctx -> ctx.response.text("n=" + ctx.request.queryParams["n"]); null },
        get("/json") { ctx -> ctx.response.contentType = "application/json"; ctx.response.write(json.encodeToByteArray()); null },
        post("/size") { ctx -> ctx.response.text("size=" + ctx.request.body().size); null },
        get("/peer") { ctx -> ctx.response.text(ctx.request.peerAddress + "|" + ctx.request.remoteAddress); null },
    )

    // --- HTTP/1.1 ---------------------------------------------------------------------------

    @Test
    fun http1KeepAliveServesSeveralRequestsOnOneConnection() = runBlocking {
        val server = startServer(basicRoutes())
        val client = RawClient(server.port)
        try {
            repeat(3) { i ->
                client.send(getRequest("/n?n=$i"))
                val r = client.readResponse()
                assertEquals(200, r.status)
                assertEquals("n=$i", r.text)
                assertNull(r.header("connection"), "keep-alive is the HTTP/1.1 default and must not be closed")
            }
        } finally {
            client.close()
            server.stop()
        }
    }

    @Test
    fun http1PipelinedRequestsAreAnsweredInOrder() = runBlocking {
        val server = startServer(basicRoutes())
        val client = RawClient(server.port)
        try {
            client.send((1..5).joinToString("") { getRequest("/n?n=$it") })
            for (i in 1..5) {
                val r = client.readResponse()
                assertEquals(200, r.status)
                assertEquals("n=$i", r.text)
            }
        } finally {
            client.close()
            server.stop()
        }
    }

    @Test
    fun http1ConnectionCloseIsHonoured() = runBlocking {
        val server = startServer(basicRoutes())
        val client = RawClient(server.port)
        try {
            client.send(getRequest("/hello", "Connection: close\r\n"))
            assertEquals("hello", client.readResponse().text)
            assertTrue(client.serverClosed())
        } finally {
            client.close()
            server.stop()
        }
    }

    @Test
    fun requestTranslationKeepsMethodPathQueryAndHeaders() = runBlocking {
        val routes = listOf(
            route(neton.core.http.HttpMethod.PUT, "/items/{id}") { ctx ->
                val h = ctx.request.headers
                ctx.response.text(
                    listOf(
                        ctx.request.method.name,
                        ctx.request.path,
                        ctx.request.queryParams["q"],
                        h["X-Custom"], h["x-custom"],
                        h.getAll("X-Multi").joinToString("|"),
                        ctx.request.body().decodeToString(),
                    ).joinToString(";"),
                )
                null
            },
        )
        val server = startServer(routes)
        val client = RawClient(server.port)
        try {
            client.send("PUT /items/7?q=a%20b HTTP/1.1\r\nHost: x\r\nX-Custom: v1\r\nX-Multi: one\r\nX-Multi: two\r\nContent-Length: 4\r\n\r\nbody")
            assertEquals("PUT;/items/7;a b;v1;v1;one|two;body", client.readResponse().text)
        } finally {
            client.close()
            server.stop()
        }
    }

    /** The socket peer reaches the framework: `peerAddress` always, `remoteAddress` unless X-Forwarded-For says otherwise. */
    @Test
    fun peerAddressIsTheSocketPeer() = runBlocking {
        val server = startServer(basicRoutes())
        val client = RawClient(server.port)
        try {
            client.send(getRequest("/peer"))
            assertEquals("127.0.0.1|127.0.0.1", client.readResponse().text)
            client.send(getRequest("/peer", extra = "X-Forwarded-For: 203.0.113.9, 10.0.0.1\r\n"))
            assertEquals("127.0.0.1|203.0.113.9", client.readResponse().text)
            withContext(Dispatchers.Default) {
                assertEquals("127.0.0.1|127.0.0.1", clientRequest(server.port, "/peer", h2 = true).text)
            }
        } finally {
            client.close()
            server.stop()
        }
    }

    /** A dual-stack listener sees an IPv4 client as v4-mapped; the framework gets the plain IPv4 address. */
    @Test
    fun peerAddressOfAnIpv4ClientOnADualStackListenerIsIpv4() = runBlocking {
        val server = startServer(basicRoutes(), NetonStreamOptions(host = "::", reactors = 1))
        val client = RawClient(server.port)
        try {
            client.send(getRequest("/peer"))
            assertEquals("127.0.0.1|127.0.0.1", client.readResponse().text)
        } finally {
            client.close()
            server.stop()
        }
    }

    // --- HTTP/2 on the same port --------------------------------------------------------------

    @Test
    fun h2cPriorKnowledgeAndHttp1ShareThePort() = runBlocking {
        val server = startServer(basicRoutes())
        try {
            withContext(Dispatchers.Default) {
                val h2 = clientRequest(server.port, "/hello", h2 = true)
                assertEquals(200, h2.status)
                assertEquals("HTTP/2.0", h2.version)
                assertEquals("hello", h2.text)
                val h1 = clientRequest(server.port, "/hello", h2 = false)
                assertEquals(200, h1.status)
                assertEquals("HTTP/1.1", h1.version)
                assertEquals("hello", h1.text)
                val post = clientRequest(server.port, "/size", h2 = true, method = "POST", body = ByteArray(100_000) { 7 })
                assertEquals("size=100000", post.text)
            }
        } finally {
            server.stop()
        }
    }

    // --- TLS with ALPN --------------------------------------------------------------------------

    private suspend fun tlsServer(identity: TestIdentity, alpn: List<String>): TestServer {
        val cert = writeTempFile("cert-${alpn.joinToString("_").replace('/', '-')}.pem", identity.certificate)
        val key = writeTempFile("key-${alpn.joinToString("_").replace('/', '-')}.pem", identity.key)
        return startServer(basicRoutes()) { port ->
            HttpServerConfig(port = port, tls = TlsSettings(cert, key, alpn))
        }
    }

    @Test
    fun tlsAlpnSelectsH2() = runBlocking {
        val identity = testIdentity()
        val server = tlsServer(identity, listOf("h2", "http/1.1"))
        try {
            withContext(Dispatchers.Default) {
                val r = clientRequest(server.port, "/hello", h2 = true, tls = clientTls(identity, listOf("h2", "http/1.1")))
                assertEquals("h2", r.alpn)
                assertEquals("HTTP/2.0", r.version)
                assertEquals("hello", r.text)
                // Taken from the socket before TLS wrapped it.
                val peer = clientRequest(server.port, "/peer", h2 = true, tls = clientTls(identity, listOf("h2")))
                assertEquals("127.0.0.1|127.0.0.1", peer.text)
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun tlsAlpnSelectsHttp11() = runBlocking {
        val identity = testIdentity()
        val server = tlsServer(identity, listOf("h2", "http/1.1"))
        try {
            withContext(Dispatchers.Default) {
                val r = clientRequest(server.port, "/hello", h2 = false, tls = clientTls(identity, listOf("http/1.1")))
                assertEquals("http/1.1", r.alpn)
                assertEquals("HTTP/1.1", r.version)
                assertEquals("hello", r.text)
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun tlsServerThatOnlyAdvertisesHttp11ServesHttp11ToAnH2CapableClient() = runBlocking {
        val identity = testIdentity()
        val server = tlsServer(identity, listOf("http/1.1"))
        try {
            withContext(Dispatchers.Default) {
                val r = clientRequest(server.port, "/json", h2 = false, tls = clientTls(identity, listOf("h2", "http/1.1")))
                assertEquals("http/1.1", r.alpn)
                assertEquals(json, r.text)
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun unreadableCertificateFailsStartWithThePaths() = runBlocking {
        val adapter = NetonStreamHttpAdapter(
            HttpServerConfig(port = freePort(), tls = TlsSettings("/nonexistent/cert.pem", "/nonexistent/key.pem")),
            NetonStreamOptions(host = "127.0.0.1", reactors = 1),
        )
        val error = runCatching { adapter.start(fixtureContext(emptyList())) }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error.message!!.contains("/nonexistent/cert.pem"), error.message)
    }

    @Test
    fun aStoppedAdapterRefusesToStartAgain() = runBlocking {
        val server = startServer(basicRoutes())
        server.stop()
        val error = runCatching { server.adapter.start(fixtureContext(emptyList())) }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error.message!!.contains("cannot be started again"), error.message)
    }

    @Test
    fun portInUseFailsStart() = runBlocking {
        val server = startServer(basicRoutes())
        try {
            val second = NetonStreamHttpAdapter(HttpServerConfig(port = server.port), NetonStreamOptions(host = "127.0.0.1", reactors = 1))
            val error = runCatching { second.start(fixtureContext(emptyList())) }.exceptionOrNull()
            assertNotNull(error)
            assertTrue(error.message!!.contains("failed to start"), error.message)
        } finally {
            server.stop()
        }
    }

    // --- gzip -----------------------------------------------------------------------------------

    @Test
    fun gzipOverHttp1AndH2FollowsAcceptEncoding() = runBlocking {
        val server = startServer(basicRoutes())
        try {
            val client = RawClient(server.port)
            try {
                client.send(getRequest("/json", "Accept-Encoding: gzip, deflate\r\n"))
                val gz = client.readResponse()
                assertEquals("gzip", gz.header("content-encoding"))
                assertEquals("Accept-Encoding", gz.header("vary"))
                assertEquals(gz.body.size.toString(), gz.header("content-length"))
                assertEquals(json, gunzip(gz.body).decodeToString())

                client.send(getRequest("/json"))
                val plain = client.readResponse()
                assertNull(plain.header("content-encoding"))
                assertEquals(json, plain.text)

                client.send(getRequest("/json", "Accept-Encoding: gzip;q=0\r\n"))
                assertNull(client.readResponse().header("content-encoding"))

                client.send(getRequest("/hello", "Accept-Encoding: gzip\r\n"))
                assertNull(client.readResponse().header("content-encoding"), "below the 256-byte threshold")
            } finally {
                client.close()
            }
            withContext(Dispatchers.Default) {
                val h2 = clientRequest(server.port, "/json", h2 = true, headers = mapOf("accept-encoding" to "gzip"))
                assertEquals("gzip", h2.header("content-encoding"))
                assertEquals(json, gunzip(h2.body).decodeToString())
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun compressionDisabledByConfigSendsIdentity() = runBlocking {
        val server = startServer(basicRoutes()) { HttpServerConfig(port = it, enableCompression = false) }
        val client = RawClient(server.port)
        try {
            client.send(getRequest("/json", "Accept-Encoding: gzip\r\n"))
            val r = client.readResponse()
            assertNull(r.header("content-encoding"))
            assertEquals(json, r.text)
        } finally {
            client.close()
            server.stop()
        }
    }

    // --- 413 / 503 / 504 ------------------------------------------------------------------------

    @Test
    fun declaredBodyOverTheLimitIs413OnHttp1() = runBlocking {
        val server = startServer(basicRoutes(), NetonStreamOptions(host = "127.0.0.1", reactors = 1, maxRequestBodyBytes = 1024))
        val client = RawClient(server.port)
        try {
            client.send("POST /size HTTP/1.1\r\nHost: x\r\nContent-Length: 2048\r\n\r\n")
            assertEquals(413, client.readResponse().status)
        } finally {
            client.close()
            server.stop()
        }
    }

    @Test
    fun chunkedBodyOverTheLimitIs413OnHttp1() = runBlocking {
        val server = startServer(basicRoutes(), NetonStreamOptions(host = "127.0.0.1", reactors = 1, maxRequestBodyBytes = 1024))
        val client = RawClient(server.port)
        try {
            val chunk = "x".repeat(600)
            client.send("POST /size HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n")
            client.send("258\r\n$chunk\r\n258\r\n$chunk\r\n0\r\n\r\n")
            assertEquals(413, client.readResponse().status)
            // Within the limit is still fine on a fresh connection.
            val ok = RawClient(server.port)
            try {
                ok.send("POST /size HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n258\r\n$chunk\r\n0\r\n\r\n")
                assertEquals("size=600", ok.readResponse().text)
            } finally {
                ok.close()
            }
        } finally {
            client.close()
            server.stop()
        }
    }

    @Test
    fun bodyOverTheLimitIs413OnH2() = runBlocking {
        val server = startServer(basicRoutes(), NetonStreamOptions(host = "127.0.0.1", reactors = 1, maxRequestBodyBytes = 1024))
        try {
            withContext(Dispatchers.Default) {
                val r = clientRequest(server.port, "/size", h2 = true, method = "POST", body = ByteArray(4096))
                assertEquals(413, r.status)
                val ok = clientRequest(server.port, "/size", h2 = true, method = "POST", body = ByteArray(1024))
                assertEquals("size=1024", ok.text)
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun requestsOverMaxConnectionsAre503() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val routes = basicRoutes() + get("/slow") { ctx ->
            entered.complete(Unit)
            release.await()
            ctx.response.text("slow")
            null
        }
        val server = startServer(routes) { HttpServerConfig(port = it, maxConnections = 1) }
        val first = RawClient(server.port)
        val second = RawClient(server.port)
        try {
            first.send(getRequest("/slow"))
            withTimeout(5_000) { entered.await() }
            second.send(getRequest("/hello"))
            val rejected = second.readResponse()
            assertEquals(503, rejected.status)
            assertEquals(503, envelopeCode(rejected.text).second)
            release.complete(Unit)
            assertEquals("slow", withContext(Dispatchers.Default) { first.readResponse() }.text)
            // The slot is free again.
            second.send(getRequest("/hello"))
            assertEquals("hello", second.readResponse().text)
        } finally {
            release.complete(Unit)
            first.close()
            second.close()
            server.stop()
        }
    }

    @Test
    fun handlerOverTheTimeoutIs504() = runBlocking {
        val routes = basicRoutes() + get("/sleep") { ctx ->
            delay(5_000)
            ctx.response.text("late")
            null
        }
        val server = startServer(routes) { HttpServerConfig(port = it, timeout = 200) }
        val client = RawClient(server.port)
        try {
            val started = TimeSource.Monotonic.markNow()
            client.send(getRequest("/sleep"))
            val r = withContext(Dispatchers.Default) { client.readResponse() }
            assertEquals(504, r.status)
            assertTrue(started.elapsedNow().inWholeMilliseconds < 3_000)
            assertEquals(504, envelopeCode(r.text).second)
            // The connection stays usable.
            client.send(getRequest("/hello"))
            assertEquals("hello", client.readResponse().text)
        } finally {
            client.close()
            server.stop()
        }
    }

    // --- streaming --------------------------------------------------------------------------------

    @Test
    fun sseClientThatDisconnectsEndsTheHandlersWrites() = runBlocking {
        val gone = CompletableDeferred<Int>()
        val routes = listOf(
            get("/events") { ctx ->
                ctx.response.contentType = "text/event-stream"
                ctx.response.stream {
                    var sent = 0
                    while (sent < 1_000) {
                        writeChunk("data: event-$sent\n\n")
                        sent++
                        if ((ctx.response as NetonStreamLiveResponse).clientGone) {
                            gone.complete(sent)
                            return@stream
                        }
                        delay(10)
                    }
                }
                null
            },
        )
        val server = startServer(routes)
        val client = RawClient(server.port)
        try {
            client.send(getRequest("/events"))
            val (status, headers) = client.readHead()
            assertEquals(200, status)
            assertEquals("text/event-stream", headers["content-type"]?.first())
            assertNull(headers["content-length"])
            repeat(3) { assertEquals("data: event-$it\n\n", client.nextChunk()?.decodeToString()) }
            client.close()
            val sentBeforeNoticing = withTimeout(5_000) { gone.await() }
            assertTrue(sentBeforeNoticing < 1_000, "the handler noticed only after $sentBeforeNoticing events")
            // The request is released: nothing in flight any more.
            withTimeout(5_000) { while (server.adapter.inFlightRequests > 0) delay(10) }
        } finally {
            client.close()
            server.stop()
        }
    }

    @Test
    fun sseOverH2DeliversEveryChunk() = runBlocking {
        val routes = listOf(
            get("/events") { ctx ->
                ctx.response.contentType = "text/event-stream"
                ctx.response.stream { repeat(5) { writeChunk("data: $it\n\n"); delay(5) } }
                null
            },
        )
        val server = startServer(routes)
        try {
            val r = withContext(Dispatchers.Default) { clientRequest(server.port, "/events", h2 = true) }
            assertEquals(200, r.status)
            assertEquals((0 until 5).joinToString("") { "data: $it\n\n" }, r.text)
        } finally {
            server.stop()
        }
    }

    @Test
    fun slowReaderHoldsTheStreamingHandlerBack() = runBlocking {
        val produced = AtomicLong(0)
        val finished = AtomicBoolean(false)
        val chunk = ByteArray(64 * 1024) { 'x'.code.toByte() }
        val total = 256L * 1024 * 1024
        val routes = listOf(
            get("/big") { ctx ->
                ctx.response.stream {
                    var sent = 0L
                    while (sent < total) {
                        writeChunk(chunk)
                        sent += chunk.size
                        produced.store(sent)
                        if ((ctx.response as NetonStreamLiveResponse).clientGone) break
                    }
                }
                finished.store(true)
                null
            },
        )
        val server = startServer(routes)
        val client = RawClient(server.port)
        try {
            client.send(getRequest("/big"))
            client.readHead()
            delay(1_500)
            val atPause = produced.load()
            // Socket buffers and the bounded queue, nothing like the whole 256 MiB.
            assertTrue(atPause < 64L * 1024 * 1024, "produced $atPause bytes while the client read nothing")
            assertFalse(finished.load())
            client.close()
            withTimeout(5_000) { while (!finished.load()) delay(10) }
            assertTrue(produced.load() < total)
        } finally {
            client.close()
            server.stop()
        }
    }

    // --- cookies ----------------------------------------------------------------------------------

    @Test
    fun cookiesOnTheLiveResponseAreSeparateSetCookieFields() = runBlocking {
        val routes = listOf(
            get("/login") { ctx ->
                ctx.response.cookie("session", "abc", path = "/", httpOnly = true, sameSite = Cookie.SameSite.LAX)
                ctx.response.cookie("theme", "dark", maxAge = 60, secure = true)
                ctx.response.text("ok")
                null
            },
            get("/stream-login") { ctx ->
                ctx.response.cookie("session", "streamed")
                ctx.response.stream { writeChunk("ok") }
                null
            },
        )
        val server = startServer(routes)
        val client = RawClient(server.port)
        try {
            client.send(getRequest("/login"))
            val r = client.readResponse()
            assertEquals(
                listOf("session=abc; Path=/; HttpOnly; SameSite=Lax", "theme=dark; Max-Age=60; Secure"),
                r.headers["set-cookie"],
            )
            client.send(getRequest("/stream-login"))
            val s = client.readResponse()
            assertEquals(listOf("session=streamed"), s.headers["set-cookie"])
            assertEquals("ok", s.text)
            withContext(Dispatchers.Default) {
                val h2 = clientRequest(server.port, "/login", h2 = true)
                assertEquals(2, h2.headers["set-cookie"]?.size)
            }
        } finally {
            client.close()
            server.stop()
        }
    }

    // --- graceful shutdown ------------------------------------------------------------------------

    @Test
    fun stopLetsInFlightRequestsFinishAndRefusesNewConnections() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val routes = basicRoutes() + get("/work") { ctx ->
            entered.complete(Unit)
            delay(400)
            ctx.response.text("done")
            null
        }
        val server = startServer(routes)
        val busy = RawClient(server.port)
        val idle = RawClient(server.port)
        try {
            idle.send(getRequest("/hello"))
            assertEquals("hello", idle.readResponse().text)
            busy.send(getRequest("/work"))
            withTimeout(5_000) { entered.await() }
            val started = TimeSource.Monotonic.markNow()
            val stopping = async(Dispatchers.Default) { server.stop() }
            val answer = withContext(Dispatchers.Default) { busy.readResponse() }
            assertEquals(200, answer.status)
            assertEquals("done", answer.text)
            stopping.await()
            assertTrue(started.elapsedNow().inWholeMilliseconds < 5_000)
            // The idle keep-alive connection was closed, not left hanging.
            assertTrue(idle.serverClosed())
            val refused = runCatching { RawClient(server.port, receiveTimeoutSeconds = 1).close() }
            assertTrue(refused.isFailure, "a new connection was accepted after stop")
        } finally {
            busy.close()
            idle.close()
        }
    }

    @Test
    fun stopCutsOffRequestsThatOutliveTheGracePeriod() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val cancelled = AtomicInt(0)
        val routes = listOf(
            get("/forever") { ctx ->
                entered.complete(Unit)
                try {
                    delay(60_000)
                } finally {
                    cancelled.addAndFetch(1)
                }
                ctx.response.text("never")
                null
            },
        )
        // timeout = 0: no per-request timeout, and hyper4k's 5 s grace on stop.
        val server = startServer(routes) { HttpServerConfig(port = it, timeout = 0) }
        val client = RawClient(server.port)
        try {
            client.send(getRequest("/forever"))
            withTimeout(5_000) { entered.await() }
            val started = TimeSource.Monotonic.markNow()
            server.stop()
            val took = started.elapsedNow().inWholeMilliseconds
            assertTrue(took in 4_500..9_000, "stop took $took ms, expected the 5 s grace")
            assertEquals(1, cancelled.load())
            // The connection was closed without an answer.
            assertTrue(withContext(Dispatchers.Default) { client.serverClosed() })
        } finally {
            client.close()
        }
    }

    /** The Neton error envelope's code, and the status it stands for. */
    private fun envelopeCode(body: String): Pair<Int, Int> {
        val code = Json.parseToJsonElement(body).jsonObject["code"]?.jsonPrimitive?.int ?: -1
        val status = when (code) {
            neton.core.http.NetonErrorCode.TIMEOUT -> 504
            neton.core.http.NetonErrorCode.SERVICE_UNAVAILABLE -> 503
            else -> -1
        }
        return code to status
    }
}

internal fun gunzip(data: ByteArray): ByteArray = kotlinx.cinterop.memScoped {
    val stream = alloc<platform.zlib.z_stream>()
    check(
        platform.zlib.inflateInit2_(
            stream.ptr, 15 + 16, platform.zlib.ZLIB_VERSION, kotlinx.cinterop.sizeOf<platform.zlib.z_stream>().toInt(),
        ) == platform.zlib.Z_OK,
    )
    try {
        var out = ByteArray(maxOf(1024, data.size * 8))
        data.usePinned { src ->
            stream.next_in = src.addressOf(0).reinterpret()
            stream.avail_in = data.size.convert()
            while (true) {
                if (stream.total_out.toLong() >= out.size) out = out.copyOf(out.size * 2)
                val rc = out.usePinned { dst ->
                    stream.next_out = dst.addressOf(stream.total_out.toInt()).reinterpret()
                    stream.avail_out = (out.size - stream.total_out.toInt()).convert()
                    platform.zlib.inflate(stream.ptr, platform.zlib.Z_NO_FLUSH)
                }
                if (rc == platform.zlib.Z_STREAM_END) break
                check(rc == platform.zlib.Z_OK || rc == platform.zlib.Z_BUF_ERROR) { "inflate failed: $rc" }
            }
        }
        out.copyOf(stream.total_out.toInt())
    } finally {
        platform.zlib.inflateEnd(stream.ptr)
    }
}
