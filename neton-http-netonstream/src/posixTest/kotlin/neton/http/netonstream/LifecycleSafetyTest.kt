package neton.http.netonstream

import kotlinx.coroutines.*
import neton.core.http.HttpMethod
import neton.core.http.HttpStatus
import neton.core.http.adapter.HttpServerConfig
import neton.http.Body
import neton.http.EmptyBody
import neton.http.Frame
import neton.http.Request
import neton.http.h2.http2Handshake
import neton.io.net.connect
import neton.io.net.runReactor
import neton.io.bytes.Bytes
import kotlin.test.*

class LifecycleSafetyTest {
    @Test
    fun cancelledStartupStopsTheWorkerAndReleasesPort() = runBlocking {
        ignoreSigpipe()
        val port = freePort()
        val adapter = NetonStreamHttpAdapter(HttpServerConfig(port = port), NetonStreamOptions(reactors = 1))
        val entered = CompletableDeferred<Unit>()
        adapter.afterWorkerStarted = { entered.complete(Unit); awaitCancellation() }
        val job = launch(Dispatchers.Default) { adapter.start(fixtureContext(emptyList()), null) }
        withTimeout(5_000) { entered.await() }
        job.cancel()
        withTimeout(5_000) { job.join() }
        val replacement = NetonStreamHttpAdapter(HttpServerConfig(port = port), NetonStreamOptions(reactors = 1))
        val ready = CompletableDeferred<Unit>()
        val running = launch(Dispatchers.Default) { replacement.start(fixtureContext(emptyList())) { ready.complete(Unit) } }
        try { withTimeout(5_000) { ready.await() } }
        finally { replacement.stop(); running.join() }
    }

    @Test
    fun cancelledStopStillCompletesCleanup() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val ended = CompletableDeferred<Unit>()
        val server = startServer(listOf(get("/wait") {
            entered.complete(Unit)
            try { awaitCancellation() } finally { ended.complete(Unit) }
        })) { HttpServerConfig(port = it, timeout = 300) }
        val client = RawClient(server.port)
        try {
            client.send(getRequest("/wait"))
            withTimeout(5_000) { entered.await() }
            val stopping = launch(start = CoroutineStart.UNDISPATCHED) { server.stop() }
            stopping.cancel()
            withTimeout(5_000) { stopping.join(); ended.await(); server.stop() }
            assertEquals(0, server.adapter.inFlightRequests)
            assertTrue(runCatching { RawClient(server.port).close() }.isFailure)
            withTimeout(5_000) {
                val error = assertFailsWith<IllegalStateException> {
                    server.adapter.start(fixtureContext(emptyList()), null)
                }
                assertTrue(error.message!!.contains("cannot be started again"))
            }
        } finally { client.close(); server.stop() }
    }

    @Test
    fun disconnectBeforeHeadersCancelsHandlerWithoutRequestTimeout() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val ended = CompletableDeferred<Unit>()
        val server = startServer(listOf(get("/wait") {
            entered.complete(Unit)
            try { awaitCancellation() } finally { ended.complete(Unit) }
        })) { HttpServerConfig(port = it, timeout = 0) }
        val client = RawClient(server.port)
        try {
            client.send(getRequest("/wait"))
            withTimeout(5_000) { entered.await() }
            client.close()
            withTimeout(5_000) {
                ended.await()
                while (server.adapter.inFlightRequests != 0) yield()
            }
        } finally { client.close(); server.stop() }
    }

    @Test
    fun headDiscardsProducerWithoutClosingKeepAlive() = runBlocking {
        val ended = CompletableDeferred<Unit>()
        val server = startServer(listOf(
            route(HttpMethod.HEAD, "/stream") { ctx ->
                try { ctx.response.stream { repeat(100) { writeChunk("data") } } }
                finally { ended.complete(Unit) }
                null
            },
            get("/ok") { it.response.text("ok"); null },
            route(HttpMethod.HEAD, "/buffered") { it.response.text("hello"); null },
        )) { HttpServerConfig(port = it, timeout = 0, maxConnections = 1) }
        val client = RawClient(server.port)
        try {
            client.send("HEAD /stream HTTP/1.1\r\nHost: localhost\r\n\r\n")
            assertEquals(200, client.readResponse(headRequest = true).status)
            withTimeout(5_000) { ended.await(); while (server.adapter.inFlightRequests != 0) yield() }
            client.send(getRequest("/ok"))
            assertEquals("ok", client.readResponse().text)
            client.send("HEAD /buffered HTTP/1.1\r\nHost: localhost\r\n\r\n")
            assertEquals("5", client.readResponse(headRequest = true).header("content-length"))
        } finally { client.close(); server.stop() }
    }

    private class WaitingBody(override val exactLength: Long = -1) : Body {
        val reading = CompletableDeferred<Unit>()
        override suspend fun nextFrame(): Frame? { reading.complete(Unit); awaitCancellation() }
    }

    @Test
    fun smallUploadsDoNotReserveTheirMaximumBodyLimit() = runBlocking {
        val adapter = NetonStreamHttpAdapter(HttpServerConfig(port = 0, maxConnections = 16, timeout = 0))
        adapter.bindContext(fixtureContext(emptyList()))
        val bodies = List(8) { WaitingBody() }
        val jobs = bodies.map { body -> launch { adapter.handle(Request.post("/upload").body(body)) } }
        try {
            withTimeout(5_000) { bodies.forEach { it.reading.await() } }
            assertEquals(8, adapter.inFlightRequests)
            assertEquals(8 * 2048L, adapter.reservedRequestBytes)
        } finally { jobs.forEach { it.cancelAndJoin() } }
        assertEquals(0L, adapter.reservedRequestBytes)
    }

    @Test
    fun growthIsRejectedBeforeAllocationAndReleasesTheReservation() = runBlocking {
        val adapter = NetonStreamHttpAdapter(HttpServerConfig(port = 0, maxConnections = 10, timeout = 0),
            NetonStreamOptions(maxRequestBodyBytes = 4096, maxBufferedRequestBytes = 8192))
        adapter.bindContext(fixtureContext(emptyList()))
        val waiting = WaitingBody()
        val first = launch { adapter.handle(Request.post("/upload").body(waiting)) }
        waiting.reading.await()
        try {
            val growing = object : Body {
                override suspend fun nextFrame(): Frame = Frame.Data(Bytes.copyOf(ByteArray(4096)))
            }
            assertEquals(503, adapter.handle(Request.post("/upload").body(growing)).status.asU16())
            assertEquals(2048L, adapter.reservedRequestBytes)
            assertEquals(1, adapter.inFlightRequests)
        } finally { first.cancelAndJoin() }
        assertEquals(0L, adapter.reservedRequestBytes)
    }

    @Test
    fun declaredLengthReservesBeforeTheInitialAllocation() = runBlocking {
        val adapter = NetonStreamHttpAdapter(HttpServerConfig(port = 0, maxConnections = 10, timeout = 0),
            NetonStreamOptions(maxRequestBodyBytes = 4096, maxBufferedRequestBytes = 8192))
        adapter.bindContext(fixtureContext(emptyList()))
        val waiting = WaitingBody()
        val first = launch { adapter.handle(Request.post("/upload").body(waiting)) }
        withTimeout(5_000) { waiting.reading.await() }
        try {
            val declared = WaitingBody(exactLength = 4096)
            assertEquals(503, withTimeout(5_000) {
                adapter.handle(Request.post("/upload").body(declared)).status.asU16()
            })
            assertFalse(declared.reading.isCompleted)
            assertEquals(2048L, adapter.reservedRequestBytes)
        } finally { first.cancelAndJoin() }
        assertEquals(0L, adapter.reservedRequestBytes)
    }

    @Test
    fun h2DisconnectBeforeHeadersCancelsHandler() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val ended = CompletableDeferred<Unit>()
        val server = startServer(listOf(get("/wait") {
            entered.complete(Unit)
            try { awaitCancellation() } finally { ended.complete(Unit) }
        })) { HttpServerConfig(port = it, timeout = 0) }
        try {
            withContext(Dispatchers.Default) {
                runReactor {
                    val tcp = connect("127.0.0.1", server.port)
                    coroutineScope {
                        val (sender, connection) = http2Handshake(tcp)
                        val driver = launch { runCatching { connection.run() } }
                        val pending = launch {
                            runCatching { sender.sendRequest(Request.get("http://localhost/wait").body(EmptyBody)) }
                        }
                        try { withTimeout(5_000) { entered.await() } }
                        finally { tcp.close(); pending.cancelAndJoin(); driver.cancelAndJoin() }
                    }
                }
            }
            withTimeout(5_000) { ended.await(); while (server.adapter.inFlightRequests != 0) yield() }
        } finally { server.stop() }
    }

    @Test
    fun noContentDiscardsProducerAndKeepsServing() = runBlocking {
        val ended = CompletableDeferred<Unit>()
        val server = startServer(listOf(
            get("/empty") { ctx ->
                ctx.response.status = HttpStatus.NO_CONTENT
                try { ctx.response.stream { repeat(100) { writeChunk("not sent") } } }
                finally { ended.complete(Unit) }
                null
            },
            get("/ok") { it.response.text("ok"); null },
        )) { HttpServerConfig(port = it, timeout = 0, maxConnections = 1) }
        val client = RawClient(server.port)
        try {
            client.send(getRequest("/empty"))
            assertEquals(204, client.readResponse().status)
            withTimeout(5_000) { ended.await(); while (server.adapter.inFlightRequests != 0) yield() }
            client.send(getRequest("/ok"))
            assertEquals("ok", client.readResponse().text)
        } finally { client.close(); server.stop() }
    }

    @Test
    fun admissionRejectsBeforeReadingAndCancellationReturnsReservation() = runBlocking {
        val adapter = NetonStreamHttpAdapter(HttpServerConfig(port = 0, maxConnections = 1, timeout = 0),
            NetonStreamOptions(maxRequestBodyBytes = 1024, maxBufferedRequestBytes = 2048))
        adapter.bindContext(fixtureContext(emptyList()))
        val body = WaitingBody()
        val first = launch { adapter.handle(Request.post("/upload").body(body)) }
        body.reading.await()
        try {
            val unread = WaitingBody()
            assertEquals(503, adapter.handle(Request.post("/upload").body(unread)).status.asU16())
            assertFalse(unread.reading.isCompleted)
            assertEquals(2048L, adapter.reservedRequestBytes)
        } finally { first.cancelAndJoin() }
        assertEquals(0, adapter.inFlightRequests)
        assertEquals(0L, adapter.reservedRequestBytes)
    }

    @Test
    fun bodyBudgetAndTimeoutAreIndependentOfHandlerLimit() = runBlocking {
        val adapter = NetonStreamHttpAdapter(HttpServerConfig(port = 0, maxConnections = 10, timeout = 0),
            NetonStreamOptions(maxRequestBodyBytes = 1024, maxBufferedRequestBytes = 2048, requestBodyTimeoutMillis = 200))
        adapter.bindContext(fixtureContext(emptyList()))
        val body = WaitingBody()
        val first = async { adapter.handle(Request.post("/upload").body(body)) }
        body.reading.await()
        val unread = WaitingBody()
        assertEquals(503, adapter.handle(Request.post("/upload").body(unread)).status.asU16())
        assertFalse(unread.reading.isCompleted)
        assertEquals(504, withTimeout(5_000) { first.await() }.status.asU16())
        assertEquals(0L, adapter.reservedRequestBytes)
        assertEquals(0, adapter.inFlightRequests)
    }
}
