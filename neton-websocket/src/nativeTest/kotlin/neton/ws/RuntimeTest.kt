@file:OptIn(neton.ws.spi.ExperimentalWebSocketEngineApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package neton.ws

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import neton.core.http.*
import neton.core.component.NetonContext
import neton.core.http.adapter.HttpAdapter
import neton.core.http.upgrade.UpgradedConnection
import neton.io.net.runReactor
import neton.ws.spi.*
import kotlin.coroutines.ContinuationInterceptor
import kotlin.test.*

class RuntimeTest {
    @Test fun connectionIdsArePerConnectionNotPerIdentity() = runReactor {
        val executor = currentCoroutineContext()[ContinuationInterceptor] as CoroutineDispatcher
        val runtime = WebSocketRuntime(config(), provider)
        val one = ManagedWebSocketSession(runtime, Engine(executor), handshake)
        val two = ManagedWebSocketSession(runtime, Engine(executor), handshake)
        assertNotEquals(one.connectionId, two.connectionId)
        assertEquals(one.connectionId, one.connectionId)
        assertEquals(36, one.connectionId.length)
    }
    private val handshake = HandshakeSnapshot("/", "127.0.0.1", false, null, null, emptyMap(), emptyMap(), emptyMap())
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
        val closes = mutableListOf<Int?>()
        var earlyPong = true
        var pings = 0
        var aborted = false
        var failCleanup = false
        var writeGate: CompletableDeferred<Unit>? = null
        val writeEntered = CompletableDeferred<Unit>()
        override suspend fun receive(): WebSocketEngineEvent? = events.receiveCatching().getOrNull()
        override suspend fun receive(budget: ByteBudget) = receive()
        override suspend fun writeText(text: String) { writeEntered.complete(Unit); writeGate?.await(); writes += text }
        override suspend fun writeBinary(bytes: ByteArray) { writeGate?.await(); writes += bytes.joinToString() }
        override suspend fun writePing(bytes: ByteArray) {
            pings++
            if (earlyPong) { events.send(WebSocketEngineEvent.Pong(bytes.copyOf())); yield() }
        }
        override suspend fun writeClose(code: Int?, reason: String) {
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
            val session = ManagedWebSocketSession(runtime, engine, handshake)
            val shutdown = CompletableDeferred<Unit>()
            val job = launch { session.run(shutdown) { awaitCancellation() } }
            delay(100)
            assertTrue(engine.pings >= 2)
            assertFalse(engine.aborted)
            shutdown.complete(Unit)
            job.join()
            assertEquals(1001, session.awaitClosed().sent?.code)
            assertTrue(engine.aborted)
        }
    }

    @Test fun missingPongClosesAndReclaims() = runReactor {
        withTimeout(3000) {
            val engine = Engine(currentCoroutineContext()[ContinuationInterceptor] as CoroutineDispatcher).apply { earlyPong = false }
            val runtime = WebSocketRuntime(config().apply { pingIntervalMillis = 5; pongTimeoutMillis = 20 }, provider)
            val session = ManagedWebSocketSession(runtime, engine, handshake)
            session.run(CompletableDeferred()) { awaitCancellation() }
            assertEquals(1001, session.awaitClosed().sent?.code)
            assertTrue(engine.closes.contains(1001))
            assertEquals(0L, runtime.buffered.usage)
        }
    }

    @Test fun fifoCopyAndBudgetReleasedOnClose() = runReactor {
        withTimeout(3000) {
            val engine = Engine(currentCoroutineContext()[ContinuationInterceptor] as CoroutineDispatcher)
            val runtime = WebSocketRuntime(config(), provider)
            val session = ManagedWebSocketSession(runtime, engine, handshake)
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
            val session = ManagedWebSocketSession(runtime, engine, handshake)
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
            val session = ManagedWebSocketSession(runtime, engine, handshake)
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
            val session = ManagedWebSocketSession(runtime, engine, handshake)
            session.run(CompletableDeferred()) { awaitCancellation() }
            assertEquals("Consumer stalled", session.awaitClosed().localRequest?.reason)
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

    @Test fun sequentialCollectorsResumeWithoutReplay() = runReactor {
        withTimeout(3000) {
            val engine = Engine(currentCoroutineContext()[ContinuationInterceptor] as CoroutineDispatcher)
            val runtime = WebSocketRuntime(config(), provider)
            val session = ManagedWebSocketSession(runtime, engine, handshake)
            val values = mutableListOf<String>()
            val job = launch { session.run(CompletableDeferred()) {
                values += assertIs<WebSocketMessage.Text>(it.incoming.first()).value
                values += assertIs<WebSocketMessage.Text>(it.incoming.first()).value
            } }
            engine.events.send(WebSocketEngineEvent.Text("one"))
            engine.events.send(WebSocketEngineEvent.Text("two"))
            job.join()
            assertEquals(listOf("one", "two"), values)
            assertEquals(CloseTrigger.LOCAL_NORMAL, session.awaitClosed().trigger)
        }
    }

    @Test fun normalReturnDrainsAcceptedMessagesBeforeClose() = runReactor {
        withTimeout(3000) {
            val gate = CompletableDeferred<Unit>()
            val engine = Engine(currentCoroutineContext()[ContinuationInterceptor] as CoroutineDispatcher).apply { writeGate = gate }
            val runtime = WebSocketRuntime(config().apply { writeTimeoutMillis = 1000; closeTimeoutMillis = 1000 }, provider)
            val session = ManagedWebSocketSession(runtime, engine, handshake)
            val accepted = CompletableDeferred<Unit>()
            val job = launch { session.run(CompletableDeferred()) {
                it.send("a"); engine.writeEntered.await(); it.send("b"); it.send("c")
                it.close()
                accepted.complete(Unit)
            } }
            accepted.await(); engine.writeEntered.await()
            gate.complete(Unit)
            job.join()
            assertEquals(listOf("a", "b", "c"), engine.writes)
            assertEquals(listOf<Int?>(1000), engine.closes)
            assertEquals(CloseTrigger.APPLICATION, session.awaitClosed().trigger)
            assertEquals(CloseTermination.HANDSHAKE_COMPLETE, session.awaitClosed().termination)
            assertEquals(0L, runtime.buffered.usage)
        }
    }

    @Test fun twoMiBMessageFitsPayloadBudget() = runReactor {
        withTimeout(3000) {
            val settings = config().apply { engineLimits = EngineLimits(maxMessageBytes = 2 * 1024 * 1024) }
            settings.validate()
            val engine = Engine(currentCoroutineContext()[ContinuationInterceptor] as CoroutineDispatcher)
            val runtime = WebSocketRuntime(settings, provider)
            val session = ManagedWebSocketSession(runtime, engine, handshake)
            session.run(CompletableDeferred()) { it.send("x".repeat(2 * 1024 * 1024)) }
            assertEquals(2 * 1024 * 1024, engine.writes.single().length)
            assertEquals(0L, runtime.buffered.usage)
        }
    }

    @Test fun budgetWaitDoesNotBecomeWriteTimeoutAndCloseIsNotCancellation() = runReactor {
        withTimeout(3000) {
            val engine = Engine(currentCoroutineContext()[ContinuationInterceptor] as CoroutineDispatcher)
            val runtime = WebSocketRuntime(config().apply { writeTimeoutMillis = 20 }, provider)
            assertTrue(runtime.buffered.tryReserve(runtime.buffered.limit))
            val session = ManagedWebSocketSession(runtime, engine, handshake)
            val job = launch { session.run(CompletableDeferred()) { awaitCancellation() } }
            // An independent caller must see closure, not inherit handler shutdown cancellation.
            val outcome = async(Dispatchers.Default) { runCatching { session.send("waiting") }.exceptionOrNull() }
            while (runtime.pendingCount.usage == 0L) yield()
            delay(60) // Beyond the write deadline, but no network write has started.
            assertFalse(outcome.isCompleted)
            session.close(1001)
            assertIs<WebSocketClosedException>(outcome.await())
            job.join()
            assertEquals(0L, runtime.pendingCount.usage)
            assertEquals(0L, runtime.pendingBytes.usage)
            runtime.buffered.release(runtime.buffered.limit)
        }
    }

    @Test fun failedNormalDrainIsAbnormalAndReleasesAcceptedPayloads() = runReactor {
        withTimeout(3000) {
            val engine = Engine(currentCoroutineContext()[ContinuationInterceptor] as CoroutineDispatcher).apply { writeGate = CompletableDeferred() }
            val runtime = WebSocketRuntime(config(), provider)
            val session = ManagedWebSocketSession(runtime, engine, handshake)
            session.run(CompletableDeferred()) { it.send("a"); it.send("b") }
            assertTrue(session.awaitClosed().termination in setOf(CloseTermination.WRITE_TIMEOUT, CloseTermination.CLOSE_TIMEOUT))
            assertTrue(engine.closes.isEmpty())
            assertEquals(0L, runtime.buffered.usage)
        }
    }

    @Test fun impossiblePayloadBudgetRejectedAtStartup() {
        assertFailsWith<IllegalArgumentException> {
            config().apply { engineLimits = EngineLimits(maxMessageBytes = 9 * 1024 * 1024) }.validate()
        }
    }

    @Test fun forcedCloseInterruptsNormalDrainWithoutReportingNormalClose() = runReactor {
        withTimeout(3000) {
            val gate = CompletableDeferred<Unit>()
            val engine = Engine(currentCoroutineContext()[ContinuationInterceptor] as CoroutineDispatcher).apply { writeGate = gate }
            val runtime = WebSocketRuntime(config().apply { writeTimeoutMillis = 1000; closeTimeoutMillis = 1000 }, provider)
            val session = ManagedWebSocketSession(runtime, engine, handshake)
            val accepted = CompletableDeferred<Unit>()
            val job = launch { session.run(CompletableDeferred()) {
                it.send("a"); engine.writeEntered.await(); it.send("b"); it.close()
                accepted.complete(Unit)
            } }
            accepted.await()
            session.close(1001, "Server stopping")
            gate.complete(Unit)
            job.join()
            assertEquals(listOf("a"), engine.writes)
            assertEquals(listOf<Int?>(1001), engine.closes)
            assertEquals(1001, session.awaitClosed().sent?.code)
            assertEquals(0L, runtime.buffered.usage)
        }
    }

    @Test fun cancelledObserverCannotCancelOtherObservers() = runReactor {
        withTimeout(3000) {
            val engine = Engine(currentCoroutineContext()[ContinuationInterceptor] as CoroutineDispatcher)
            val session = ManagedWebSocketSession(WebSocketRuntime(config(), provider), engine, handshake)
            val shutdown = CompletableDeferred<Unit>()
            val job = launch { session.run(shutdown) { awaitCancellation() } }
            val first = async { session.awaitClosed() }
            val second = async { session.awaitClosed() }
            yield(); first.cancelAndJoin()
            shutdown.complete(Unit); job.join()
            assertEquals(CloseTrigger.SERVER_SHUTDOWN, second.await().trigger)
            assertEquals(second.await(), session.awaitClosed())
        }
    }

    @Test fun emptyPeerCloseIsNotNormalCode1000() = runReactor {
        withTimeout(3000) {
            val engine = Engine(currentCoroutineContext()[ContinuationInterceptor] as CoroutineDispatcher)
            engine.events.send(WebSocketEngineEvent.CloseReceived(null, ""))
            val session = ManagedWebSocketSession(WebSocketRuntime(config(), provider), engine, handshake)
            session.run(CompletableDeferred()) { awaitCancellation() }
            val result = session.awaitClosed()
            assertEquals(CloseFrameInfo(null), result.received)
            assertEquals(CloseFrameInfo(null), result.sent)
            assertNull(result.localRequest)
            assertTrue(result.handshakeComplete)
            assertEquals(CloseTermination.HANDSHAKE_COMPLETE, result.termination)
        }
    }

    @Test fun businessFinallyCanAwaitClosureWithoutDeadlock() = runReactor {
        withTimeout(3000) {
            val engine = Engine(currentCoroutineContext()[ContinuationInterceptor] as CoroutineDispatcher)
            val session = ManagedWebSocketSession(WebSocketRuntime(config(), provider), engine, handshake)
            val entered = CompletableDeferred<Unit>()
            val cleaned = CompletableDeferred<CloseResult>()
            val shutdown = CompletableDeferred<Unit>()
            val job = launch { session.run(shutdown) {
                try { entered.complete(Unit); awaitCancellation() }
                finally { withContext(NonCancellable) { cleaned.complete(it.awaitClosed()) } }
            } }
            entered.await(); shutdown.complete(Unit); job.join()
            assertEquals(session.awaitClosed(), cleaned.await())
        }
    }

    @Test fun handlerSelfCancellationClosesTransport() = runReactor {
        withTimeout(3000) {
            val engine = Engine(currentCoroutineContext()[ContinuationInterceptor] as CoroutineDispatcher)
            val session = ManagedWebSocketSession(WebSocketRuntime(config(), provider), engine, handshake)
            session.run(CompletableDeferred()) { throw CancellationException("self cancelled") }
            assertTrue(engine.aborted)
            assertEquals(CloseTrigger.APPLICATION, session.awaitClosed().trigger)
        }
    }

    @Test fun throwingEngineCleanupStillCompletesAndReleasesQueues() = runReactor {
        withTimeout(3000) {
            val engine = Engine(currentCoroutineContext()[ContinuationInterceptor] as CoroutineDispatcher).apply { failCleanup = true }
            val runtime = WebSocketRuntime(config(), provider)
            assertTrue(runtime.buffered.tryReserve(1024))
            engine.events.send(WebSocketEngineEvent.Text("queued", BudgetLease(runtime.buffered, 1024)))
            val session = ManagedWebSocketSession(runtime, engine, handshake)
            val shutdown = CompletableDeferred<Unit>()
            val job = launch { session.run(shutdown) { awaitCancellation() } }
            while (!engine.events.isEmpty) yield()
            shutdown.complete(Unit)
            job.join()
            assertNotNull(session.awaitClosed())
            assertTrue(engine.aborted)
            assertEquals(0L, runtime.buffered.usage)
        }
    }
}
