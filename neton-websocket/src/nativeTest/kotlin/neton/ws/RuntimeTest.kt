@file:OptIn(neton.ws.spi.ExperimentalWebSocketEngineApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package neton.ws

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import neton.core.http.*
import neton.core.component.NetonContext
import neton.core.http.adapter.HttpAdapter
import neton.core.http.upgrade.UpgradedConnection
import neton.io.net.runReactor
import neton.ws.spi.*
import kotlin.coroutines.ContinuationInterceptor
import kotlin.test.*

class RuntimeTest {
    private val context = object : HttpContext {
        override val traceId = "test"
        override val attributes = mutableMapOf<String, Any>()
        override val request: HttpRequest get() = error("unused")
        override val response: HttpResponse get() = error("unused")
        override val session: HttpSession get() = error("unused")
    }
    private val provider = object : WebSocketEngineProvider {
        override val name = "fake"
        override val capabilities = WebSocketEngineCapability.entries.toSet()
        override fun supports(adapter: HttpAdapter) = true
        override fun handshake(request: HandshakeRequest, offer: HandshakeOffer): HandshakeResult = error("unused")
        override suspend fun open(connection: UpgradedConnection, negotiated: Negotiated, limits: EngineLimits): WebSocketEngineConnection = error("unused")
    }
    private class Engine(override val executor: CoroutineDispatcher) : WebSocketEngineConnection {
        val events = Channel<WebSocketEngineEvent>(Channel.UNLIMITED)
        val writes = mutableListOf<String>()
        val closes = mutableListOf<Int>()
        var earlyPong = true
        var pings = 0
        var aborted = false
        var failCleanup = false
        var writeGate: CompletableDeferred<Unit>? = null
        override suspend fun receive(): WebSocketEngineEvent? = events.receiveCatching().getOrNull()
        override suspend fun receive(budget: ByteBudget) = receive()
        override suspend fun writeText(text: String) { writeGate?.await(); writes += text }
        override suspend fun writeBinary(bytes: ByteArray) { writeGate?.await(); writes += bytes.joinToString() }
        override suspend fun writePing(bytes: ByteArray) {
            pings++
            if (earlyPong) { events.send(WebSocketEngineEvent.Pong(bytes.copyOf())); yield() }
        }
        override suspend fun writeClose(code: Int, reason: String) {
            closes += code
            events.send(WebSocketEngineEvent.CloseReceived(code, reason))
        }
        override fun discardData() { if (failCleanup) error("discard failed") }
        override fun abort() { aborted = true; events.close(); writeGate?.cancel(); if (failCleanup) error("abort failed") }
    }
    private fun config() = WebSocketConfig().apply {
        pingIntervalMillis = 0; closeTimeoutMillis = 100; handlerShutdownMillis = 100
        consumerTimeoutMillis = 100; writeTimeoutMillis = 100
    }

    @Test fun earlyPongAndQuietConnectionRemainAlive() = runReactor {
        withTimeout(3000) {
            val engine = Engine(currentCoroutineContext()[ContinuationInterceptor] as CoroutineDispatcher)
            val runtime = WebSocketRuntime(config().apply { pingIntervalMillis = 10; pongTimeoutMillis = 20 }, provider)
            val session = ManagedWebSocketSession(runtime, engine, context, null)
            val shutdown = CompletableDeferred<Unit>()
            val job = launch { session.run(shutdown) { awaitCancellation() } }
            delay(100)
            assertTrue(engine.pings >= 2)
            assertFalse(session.closed.isCompleted)
            shutdown.complete(Unit)
            job.join()
            assertEquals(1001, session.closed.await().code)
            assertTrue(engine.aborted)
        }
    }

    @Test fun missingPongClosesAndReclaims() = runReactor {
        withTimeout(3000) {
            val engine = Engine(currentCoroutineContext()[ContinuationInterceptor] as CoroutineDispatcher).apply { earlyPong = false }
            val runtime = WebSocketRuntime(config().apply { pingIntervalMillis = 5; pongTimeoutMillis = 20 }, provider)
            val session = ManagedWebSocketSession(runtime, engine, context, null)
            session.run(CompletableDeferred()) { awaitCancellation() }
            assertEquals(1001, session.closed.await().code)
            assertTrue(engine.closes.contains(1001))
            assertEquals(0L, runtime.buffered.usage)
        }
    }

    @Test fun fifoCopyAndBudgetReleasedOnClose() = runReactor {
        withTimeout(3000) {
            val engine = Engine(currentCoroutineContext()[ContinuationInterceptor] as CoroutineDispatcher)
            val runtime = WebSocketRuntime(config(), provider)
            val session = ManagedWebSocketSession(runtime, engine, context, null)
            val sent = CompletableDeferred<Unit>()
            val shutdown = CompletableDeferred<Unit>()
            val job = launch { session.run(shutdown) {
                val bytes = byteArrayOf(1, 2)
                it.send(WebSocketMessage.Binary(bytes)); bytes[0] = 9
                it.send("second")
                sent.complete(Unit)
                awaitCancellation()
            } }
            sent.await()
            while (engine.writes.size != 2) yield()
            assertEquals(listOf("1, 2", "second"), engine.writes)
            shutdown.complete(Unit); job.join()
            assertEquals(0L, runtime.buffered.usage)
            assertEquals(0L, runtime.pendingBytes.usage)
            assertEquals(0L, runtime.pendingCount.usage)
        }
    }

    @Test fun saturatedSenderIsWokenByClose() = runReactor {
        withTimeout(3000) {
            val engine = Engine(currentCoroutineContext()[ContinuationInterceptor] as CoroutineDispatcher).apply { writeGate = CompletableDeferred() }
            val runtime = WebSocketRuntime(config().apply { queueCapacity = 1 }, provider)
            val session = ManagedWebSocketSession(runtime, engine, context, null)
            val first = CompletableDeferred<Unit>()
            val failed = CompletableDeferred<Boolean>()
            val shutdown = CompletableDeferred<Unit>()
            val job = launch { session.run(shutdown) {
                it.send("one"); first.complete(Unit)
                failed.complete(runCatching { it.send("two") }.isFailure)
                awaitCancellation()
            } }
            first.await()
            session.close(1001)
            assertTrue(failed.await())
            job.join()
            assertEquals(0L, runtime.buffered.usage)
            assertEquals(0L, runtime.pendingBytes.usage)
        }
    }

    @Test fun shutdownAlreadyRequestedDoesNotRunHandler() = runReactor {
        withTimeout(3000) {
            val engine = Engine(currentCoroutineContext()[ContinuationInterceptor] as CoroutineDispatcher)
            val runtime = WebSocketRuntime(config(), provider)
            val session = ManagedWebSocketSession(runtime, engine, context, null)
            val shutdown = CompletableDeferred<Unit>().apply { complete(Unit) }
            var ran = false
            session.run(shutdown) { ran = true }
            assertFalse(ran)
            assertTrue(engine.aborted)
        }
    }

    @Test fun utf8BudgetAndLimits() {
        for (text in listOf("", "ascii", "中文", "\uD83D\uDE00")) assertEquals(text.encodeToByteArray().size.toLong(), utf8Bytes(text))
        val budget = RuntimeBudget(10)
        assertTrue(budget.tryReserve(10)); assertFalse(budget.tryReserve(1))
        val lease = BudgetLease(budget, 10); lease.release(); lease.release()
        assertEquals(0L, budget.usage)
        assertFailsWith<IllegalArgumentException> { WebSocketConfig().apply { maxBufferedBytes = 1 }.validate() }
    }

    @Test fun stalledConsumerIsNotReportedAsPongFailure() = runReactor {
        withTimeout(3000) {
            val engine = Engine(currentCoroutineContext()[ContinuationInterceptor] as CoroutineDispatcher).apply { earlyPong = false }
            val runtime = WebSocketRuntime(config().apply { queueCapacity = 1; pingIntervalMillis = 10; pongTimeoutMillis = 10; consumerTimeoutMillis = 60 }, provider)
            repeat(2) {
                assertTrue(runtime.buffered.tryReserve(1024))
                engine.events.send(WebSocketEngineEvent.Text("data", BudgetLease(runtime.buffered, 1024)))
            }
            val session = ManagedWebSocketSession(runtime, engine, context, null)
            session.run(CompletableDeferred()) { awaitCancellation() }
            assertEquals("Consumer stalled", session.closed.await().reason)
            assertEquals(0L, runtime.buffered.usage)
        }
    }

    @Test fun configurationIsSnapshotted() = runBlocking {
        val config = config()
        val context = NetonContext(emptyArray())
        WebSocketComponent().init(context, config)
        config.maxConnections = 1
        context.get<WebSocketConfig>().maxConnections = 2
        assertEquals(16384, context.get<WebSocketRuntime>().config.maxConnections)
    }

    @Test fun throwingEngineCleanupStillCompletesAndReleasesQueues() = runReactor {
        withTimeout(3000) {
            val engine = Engine(currentCoroutineContext()[ContinuationInterceptor] as CoroutineDispatcher).apply { failCleanup = true }
            val runtime = WebSocketRuntime(config(), provider)
            assertTrue(runtime.buffered.tryReserve(1024))
            engine.events.send(WebSocketEngineEvent.Text("queued", BudgetLease(runtime.buffered, 1024)))
            val session = ManagedWebSocketSession(runtime, engine, context, null)
            val shutdown = CompletableDeferred<Unit>()
            val job = launch { session.run(shutdown) { awaitCancellation() } }
            while (!engine.events.isEmpty) yield()
            shutdown.complete(Unit)
            job.join()
            assertTrue(session.closed.isCompleted)
            assertTrue(engine.aborted)
            assertEquals(0L, runtime.buffered.usage)
        }
    }
}
