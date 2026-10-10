@file:OptIn(neton.ws.spi.ExperimentalWebSocketEngineApi::class, kotlin.concurrent.atomics.ExperimentalAtomicApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package neton.ws

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.selects.onTimeout
import neton.core.http.HttpContext
import neton.logging.LoggerFactory
import neton.ws.spi.*
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.random.Random
import kotlin.time.TimeSource

internal class ManagedWebSocketSession(
    private val runtime: WebSocketRuntime,
    private val engine: WebSocketEngineConnection,
    override val context: HttpContext,
    override val subprotocol: String?,
) : WebSocketSession {
    private val config = runtime.config
    private val start = TimeSource.Monotonic.markNow()
    private fun now() = start.elapsedNow().inWholeMilliseconds
    private fun warn(event: String) {
        runCatching { context.getApplicationContext()?.getOrNull(LoggerFactory::class)?.get("neton.websocket")?.warn(event) }
    }
    private val inLocal = RuntimeBudget(config.maxQueuedBytesPerConnection)
    private val outLocal = RuntimeBudget(config.maxQueuedBytesPerConnection)
    private val inboundBudget = CombinedBudget(runtime.buffered, inLocal)
    private val outboundBytes = CombinedBudget(runtime.buffered, outLocal)
    private val slots = RuntimeBudget(config.queueCapacity.toLong())
    private val outboundBudget = object : ByteBudget {
        override fun tryReserve(bytes: Long): Boolean {
            if (!slots.tryReserve(1)) return false
            if (outboundBytes.tryReserve(bytes)) return true
            slots.release(1)
            return false
        }
        override fun release(bytes: Long) { slots.release(1); outboundBytes.release(bytes) }
    }
    private val senders = RuntimeBudget(config.maxSuspendedSends.toLong())
    private class Entry(val message: WebSocketMessage, val lease: BudgetLease)
    private val inbound = Channel<Entry>(config.queueCapacity, onUndeliveredElement = { it.lease.release() })
    private val outbound = Channel<Entry>(config.queueCapacity, onUndeliveredElement = { it.lease.release() })
    private val closing = CompletableDeferred<WebSocketClose>()
    private val finished = CompletableDeferred<WebSocketClose>()
    private val closedResult = CompletableDeferred<WebSocketClose>()
    override val closed: Deferred<WebSocketClose> get() = closedResult
    private val collecting = AtomicBoolean(false)
    private var peerClosed = false
    private var closeWritten = false
    private var drainOutbound = false
    private var closeReason: WebSocketClose? = null
    private var readPaused = false
    private var lastInbound = 0L
    private var pendingPing: ByteArray? = null
    private var pingFlushedAt: Long? = null
    private val pingReady = Channel<ByteArray>(1)
    private val heartbeatWake = Channel<Unit>(Channel.CONFLATED)

    override val incoming: Flow<WebSocketMessage> = flow {
        check(collecting.compareAndSet(false, true)) { "Only one incoming collector is allowed" }
        try {
            for (entry in inbound) {
                // Ownership passes to business code, whose retained messages are outside queue accounting.
                entry.lease.release()
                emit(entry.message)
            }
        } finally { collecting.store(false) }
    }

    override suspend fun send(message: WebSocketMessage) {
        val bytes = when (message) {
            is WebSocketMessage.Text -> utf8Bytes(message.value)
            is WebSocketMessage.Binary -> message.bytes.size.toLong()
        }
        require(bytes <= config.engineLimits.maxMessageBytes) { "Message exceeds maxMessageBytes" }
        withContext(engine.executor) {
            val retained = bytes * 2
            var localCount = false
            var globalCount = false
            var held = false
            try {
                while (true) {
                    currentCoroutineContext().ensureActive()
                    if (closing.isCompleted || finished.isCompleted) throw WebSocketClosedException()
                    val generation = runtime.buffered.changed.value
                    if (outboundBudget.tryReserve(bytes)) {
                        val lease = BudgetLease(outboundBudget, bytes)
                        var accepted = false
                        try {
                            val owned = when (message) {
                                is WebSocketMessage.Text -> message
                                is WebSocketMessage.Binary -> WebSocketMessage.Binary(message.bytes.copyOf())
                            }
                            accepted = outbound.trySend(Entry(owned, lease)).isSuccess
                            if (accepted) return@withContext
                        } finally { if (!accepted) lease.release() }
                    }
                    // Only waiting sends need retention admission; the ready path avoids these atomics.
                    if (!localCount) {
                        localCount = senders.tryReserve(1)
                        if (!localCount) throw WebSocketCapacityException()
                        globalCount = runtime.pendingCount.tryReserve(1)
                        if (!globalCount) throw WebSocketCapacityException()
                        held = runtime.pendingBytes.tryReserve(retained)
                        if (!held) throw WebSocketCapacityException()
                    }
                    coroutineScope {
                        val changed = async { runtime.buffered.changed.first { it != generation } }
                        try { select<Unit> {
                            closing.onAwait { throw WebSocketClosedException() }
                            changed.onAwait { }
                        } } finally { changed.cancel() }
                    }
                }
            } finally {
                if (held) runtime.pendingBytes.release(retained)
                if (globalCount) runtime.pendingCount.release(1)
                if (localCount) senders.release(1)
            }
        }
    }

    override suspend fun close(code: Int, reason: String) {
        require(code in setOf(1000, 1001, 1002, 1003, 1007, 1008, 1009, 1011, 1012, 1013, 1014) || code in 3000..4999)
        require(utf8Bytes(reason) <= 123)
        withContext(engine.executor) { requestClose(WebSocketClose(code, reason), drain = code == 1000) }
    }

    private fun requestClose(reason: WebSocketClose, drain: Boolean = false) {
        if (closing.complete(reason)) {
            closeReason = reason
            drainOutbound = drain
            pendingPing = null
            pingFlushedAt = null
            if (runCatching { engine.discardData() }.isFailure) {
                warn("websocket.engine.discard.failed")
                finished.complete(WebSocketClose(1006, "Engine cleanup failed"))
            }
            inbound.cancel()
            if (drain) outbound.close() else outbound.cancel()
            heartbeatWake.trySend(Unit)
        } else if (!drain) {
            // Peer close, shutdown or failure can interrupt an earlier normal drain.
            if (drainOutbound) closeReason = reason
            drainOutbound = false
            outbound.cancel()
        }
    }

    suspend fun run(serverShutdown: Deferred<Unit>, handler: suspend (WebSocketSession) -> Unit) {
        var business: Job? = null
        val businessParent = SupervisorJob()
        var result = WebSocketClose(1006, "Transport closed")
        try {
            coroutineScope {
                val reader = launch {
                    while (true) {
                        try {
                            when (val event = engine.receive(inboundBudget)) {
                                null -> { finished.complete(WebSocketClose(1006, "Transport EOF")); return@launch }
                                is WebSocketEngineEvent.Text -> deliver(Entry(WebSocketMessage.Text(event.text), event.lease))
                                is WebSocketEngineEvent.Binary -> deliver(Entry(WebSocketMessage.Binary(event.bytes), event.lease))
                                is WebSocketEngineEvent.Pong -> {
                                    lastInbound = now()
                                    if (pendingPing?.contentEquals(event.bytes) == true) { pendingPing = null; pingFlushedAt = null }
                                    heartbeatWake.trySend(Unit)
                                }
                                is WebSocketEngineEvent.CloseReceived -> {
                                    peerClosed = true
                                    val close = WebSocketClose(event.code ?: 1000, event.reason)
                                    requestClose(close)
                                    if (closeWritten) finished.complete(close)
                                    return@launch
                                }
                            }
                        } catch (e: CancellationException) {
                            if (!closing.isCompleted || !currentCoroutineContext().isActive) throw e
                        } catch (_: WebSocketCapacityException) {
                            requestClose(WebSocketClose(1013, "Resource pressure"))
                        } catch (_: Exception) {
                            finished.complete(WebSocketClose(1006, "Read failed"))
                            return@launch
                        }
                    }
                }
                val writer = launch {
                    try {
                        while (true) {
                            select<Unit> {
                                closing.onAwait { reason ->
                                    while (drainOutbound) {
                                        val entry = outbound.tryReceive().getOrNull() ?: break
                                        writeEntry(entry)
                                    }
                                    val effectiveReason = closeReason ?: reason
                                    withTimeout(config.writeTimeoutMillis) { engine.writeClose(effectiveReason.code, effectiveReason.reason) }
                                    closeWritten = true
                                    if (peerClosed) finished.complete(effectiveReason)
                                }
                                pingReady.onReceive { payload ->
                                    withTimeout(config.writeTimeoutMillis) { engine.writePing(payload) }
                                    if (pendingPing?.contentEquals(payload) == true) pingFlushedAt = now()
                                    heartbeatWake.trySend(Unit)
                                }
                                outbound.onReceiveCatching { received ->
                                    val entry = received.getOrNull() ?: return@onReceiveCatching
                                    writeEntry(entry)
                                }
                            }
                            if (closeWritten) return@launch
                        }
                    } catch (_: Exception) {
                        finished.complete(if (peerClosed) closing.await() else WebSocketClose(1006, "Write blocked or failed"))
                    }
                }
                val closingDeadline = launch {
                    val reason = closing.await()
                    delay(config.closeTimeoutMillis)
                    finished.complete(if (closeWritten) closeReason ?: reason else WebSocketClose(1006, "Close drain timed out"))
                }
                val stop = launch {
                    select<Unit> { serverShutdown.onAwait { }; runtime.shutdown.onAwait { } }
                    requestClose(WebSocketClose(1001, "Server stopping"))
                }
                val heartbeat = launch { heartbeat() }
                // Business code never runs on the I/O executor. Its cancellation cannot prevent transport cleanup.
                if (serverShutdown.isCompleted || runtime.shutdown.isCompleted) requestClose(WebSocketClose(1001, "Server stopping"))
                business = if (closing.isCompleted) null else CoroutineScope(businessParent + Dispatchers.Default).launch {
                    try { handler(this@ManagedWebSocketSession); close() }
                    catch (_: TimeoutCancellationException) { runCatching { close(1001, "Handler timed out") } }
                    catch (_: WebSocketCapacityException) { runCatching { close(1013, "Resource pressure") } }
                    catch (_: CancellationException) { }
                    catch (_: Exception) { runCatching { close(1011, "Handler failed") } }
                }
                try { result = finished.await() }
                finally {
                    requestClose(result)
                    runCatching { engine.abort() }.onFailure { warn("websocket.engine.abort.failed") }
                    reader.cancel(); writer.cancel(); heartbeat.cancel(); stop.cancel(); closingDeadline.cancel()
                    business?.cancel()
                }
            }
        } finally {
            runCatching { engine.abort() }.onFailure { warn("websocket.engine.abort.failed") }
            inbound.cancel(); outbound.cancel()
            businessParent.cancel()
            try {
                val complete = withContext(NonCancellable) {
                    withTimeoutOrNull(config.handlerShutdownMillis) { business?.join(); true } ?: false
                }
                if (!complete) warn("websocket.handler.shutdown.timeout")
            } finally { closedResult.complete(result) }
        }
    }

    private suspend fun deliver(entry: Entry) {
        lastInbound = now()
        if (closing.isCompleted) { entry.lease.release(); return }
        if (inbound.trySend(entry).isSuccess) return
        readPaused = true
        heartbeatWake.trySend(Unit)
        var accepted = false
        try {
            withTimeout(config.consumerTimeoutMillis) { inbound.send(entry); accepted = true }
        } catch (_: TimeoutCancellationException) {
            requestClose(WebSocketClose(1011, "Consumer stalled"))
        } finally {
            readPaused = false
            // Pause in observing Pong is local backpressure, not evidence of peer failure.
            if (pingFlushedAt != null) pingFlushedAt = now()
            heartbeatWake.trySend(Unit)
            if (!accepted) entry.lease.release()
        }
    }

    private suspend fun writeEntry(entry: Entry) {
        try {
            withTimeout(config.writeTimeoutMillis) {
                when (val message = entry.message) {
                    is WebSocketMessage.Text -> engine.writeText(message.value)
                    is WebSocketMessage.Binary -> engine.writeBinary(message.bytes)
                }
            }
        } finally { entry.lease.release() }
    }

    private suspend fun heartbeat() {
        var nextPing = if (config.pingIntervalMillis == 0L) Long.MAX_VALUE else Random.nextLong(1, config.pingIntervalMillis + 1)
        var sequence = 0L
        while (currentCoroutineContext().isActive && !closing.isCompleted) {
            val time = now()
            if (!readPaused) {
                if (config.idleTimeoutMillis > 0 && time - lastInbound >= config.idleTimeoutMillis) {
                    requestClose(WebSocketClose(1001, "Inbound idle")); return
                }
                val flushed = pingFlushedAt
                if (flushed != null && config.pongTimeoutMillis > 0 && time - flushed >= config.pongTimeoutMillis) {
                    requestClose(WebSocketClose(1001, "Pong timeout")); return
                }
                if (time >= nextPing && (pendingPing == null || config.pongTimeoutMillis == 0L)) {
                    val value = ++sequence
                    val payload = ByteArray(8) { (value ushr (it * 8)).toByte() }
                    // The writer cannot run until this executor yields; registration precedes its write.
                    if (pingReady.trySend(payload).isSuccess) {
                        pendingPing = payload
                        pingFlushedAt = null
                    }
                    nextPing = time + config.pingIntervalMillis
                }
            } else if (pingFlushedAt != null) {
                // We cannot observe Pong while deliberately applying inbound backpressure.
                pingFlushedAt = time
            }
            val due = if (readPaused) Long.MAX_VALUE else minOf(
                if (pendingPing == null || config.pongTimeoutMillis == 0L) nextPing else Long.MAX_VALUE,
                pingFlushedAt?.takeIf { config.pongTimeoutMillis > 0 }?.plus(config.pongTimeoutMillis) ?: Long.MAX_VALUE,
                if (config.idleTimeoutMillis > 0) lastInbound + config.idleTimeoutMillis else Long.MAX_VALUE)
            val wait = (due - now()).coerceAtLeast(1)
            select<Unit> { heartbeatWake.onReceive { }; onTimeout(wait) { } }
        }
    }
}
