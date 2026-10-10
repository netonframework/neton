@file:OptIn(neton.ws.spi.ExperimentalWebSocketEngineApi::class, kotlin.concurrent.atomics.ExperimentalAtomicApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package neton.ws

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.selects.onTimeout
import neton.ws.spi.*
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.random.Random
import kotlin.time.TimeSource

internal class ManagedWebSocketSession(
    private val runtime: WebSocketRuntime,
    private val engine: WebSocketEngineConnection,
    override val handshake: HandshakeInfo,
    private val warning: (String) -> Unit = {},
) : WebSocketSession {
    @OptIn(kotlin.uuid.ExperimentalUuidApi::class)
    override val sessionId: String = kotlin.uuid.Uuid.random().toString()
    private val config = runtime.config
    private val start = TimeSource.Monotonic.markNow()
    private fun now() = start.elapsedNow().inWholeMilliseconds
    private fun warn(event: String) {
        runCatching { warning(event) }
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
    private val closing = CompletableDeferred<CloseFrameInfo>()
    private val finished = CompletableDeferred<CloseTermination>()
    private val closedResult = CompletableDeferred<CloseResult>()
    override suspend fun awaitClosed(): CloseResult = closedResult.await()
    private var trigger = CloseTrigger.TRANSPORT_FAILURE
    private var localRequest: CloseFrameInfo? = null
    private var sentClose: CloseFrameInfo? = null
    private var receivedClose: CloseFrameInfo? = null
    private val collecting = AtomicBoolean(false)
    private var peerClosed = false
    private var closeWritten = false
    private var drainOutbound = false
    private var closeReason: CloseFrameInfo? = null
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
            val retained = bytes
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
        withContext(engine.executor) { requestClose(CloseFrameInfo(code, reason), drain = code == 1000) }
    }

    private fun requestClose(reason: CloseFrameInfo, drain: Boolean = false, cause: CloseTrigger = CloseTrigger.APPLICATION) {
        if (closing.complete(reason)) {
            trigger = cause
            if (cause != CloseTrigger.PEER) localRequest = reason
            closeReason = reason
            drainOutbound = drain
            pendingPing = null
            pingFlushedAt = null
            if (runCatching { engine.discardData() }.isFailure) {
                warn("websocket.engine.discard.failed")
                finished.complete(CloseTermination.TRANSPORT_FAILURE)
            }
            inbound.cancel()
            if (drain) outbound.close() else outbound.cancel()
            heartbeatWake.trySend(Unit)
        } else if (!drain) {
            // Peer close, shutdown or failure can interrupt an earlier normal drain.
            if (drainOutbound && !closeWritten) closeReason = reason
            drainOutbound = false
            outbound.cancel()
        }
    }

    suspend fun run(serverShutdown: Deferred<Unit>, handler: suspend (WebSocketSession) -> Unit) {
        var business: Job? = null
        val businessParent = SupervisorJob()
        var termination = CloseTermination.FORCED_ABORT
        try {
            coroutineScope {
                val reader = launch {
                    try {
                        while (true) {
                            when (val event = engine.receive(inboundBudget)) {
                                WebSocketEngineEvent.DataRejected -> requestClose(CloseFrameInfo(1008, "Inbound data forbidden"))
                                null -> { finished.complete(CloseTermination.PEER_EOF); return@launch }
                                is WebSocketEngineEvent.Text -> deliver(Entry(WebSocketMessage.Text(event.text), event.lease))
                                is WebSocketEngineEvent.Binary -> deliver(Entry(WebSocketMessage.Binary(event.bytes), event.lease))
                                is WebSocketEngineEvent.Pong -> {
                                    lastInbound = now()
                                    if (pendingPing?.contentEquals(event.bytes) == true) { pendingPing = null; pingFlushedAt = null }
                                    heartbeatWake.trySend(Unit)
                                }
                                is WebSocketEngineEvent.CloseReceived -> {
                                    peerClosed = true
                                    receivedClose = CloseFrameInfo(event.code, event.reason)
                                    requestClose(receivedClose!!, cause = CloseTrigger.PEER)
                                    if (closeWritten) finished.complete(CloseTermination.HANDSHAKE_COMPLETE)
                                    return@launch
                                }
                            }
                        }
                    } catch (e: CancellationException) {
                        if (!currentCoroutineContext().isActive) throw e
                        finished.complete(CloseTermination.TRANSPORT_FAILURE)
                    } catch (_: WebSocketCapacityException) {
                        requestClose(CloseFrameInfo(1013, "Resource pressure"), cause = CloseTrigger.RESOURCE_PRESSURE)
                        // The failed read cannot be resumed safely; bound cleanup with the close deadline.
                    } catch (_: Exception) {
                        finished.complete(CloseTermination.TRANSPORT_FAILURE)
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
                                    sentClose = effectiveReason
                                    closeWritten = true
                                    if (peerClosed) finished.complete(CloseTermination.HANDSHAKE_COMPLETE)
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
                    } catch (_: TimeoutCancellationException) {
                        finished.complete(CloseTermination.WRITE_TIMEOUT)
                    } catch (e: CancellationException) {
                        if (!currentCoroutineContext().isActive) throw e
                        finished.complete(CloseTermination.TRANSPORT_FAILURE)
                    } catch (_: Exception) {
                        finished.complete(CloseTermination.TRANSPORT_FAILURE)
                    }
                }
                val closingDeadline = launch {
                    closing.await()
                    delay(config.closeTimeoutMillis)
                    finished.complete(CloseTermination.CLOSE_TIMEOUT)
                }
                val stop = launch {
                    select<Unit> { serverShutdown.onAwait { }; runtime.shutdown.onAwait { } }
                    requestClose(CloseFrameInfo(1001, "Server stopping"), cause = CloseTrigger.SERVER_SHUTDOWN)
                }
                val heartbeat = launch { heartbeat() }
                if (serverShutdown.isCompleted || runtime.shutdown.isCompleted) {
                    requestClose(CloseFrameInfo(1001, "Server stopping"), cause = CloseTrigger.SERVER_SHUTDOWN)
                }
                business = if (closing.isCompleted) null else CoroutineScope(businessParent + Dispatchers.Default).launch {
                    try {
                        // A handler's structured children finish before normal close is requested.
                        coroutineScope { handler(this@ManagedWebSocketSession) }
                        withContext(engine.executor) {
                            requestClose(CloseFrameInfo(1000), drain = true, cause = CloseTrigger.LOCAL_NORMAL)
                        }
                    } catch (_: WebSocketCapacityException) {
                        withContext(NonCancellable + engine.executor) {
                            requestClose(CloseFrameInfo(1013, "Resource pressure"), cause = CloseTrigger.RESOURCE_PRESSURE)
                        }
                    } catch (_: CancellationException) {
                        withContext(NonCancellable + engine.executor) {
                            if (!finished.isCompleted && !closing.isCompleted)
                                requestClose(CloseFrameInfo(1001, "Handler cancelled"))
                        }
                    } catch (_: Exception) {
                        withContext(NonCancellable + engine.executor) {
                            requestClose(CloseFrameInfo(1011, "Handler failed"), cause = CloseTrigger.HANDLER_FAILURE)
                        }
                    }
                }
                try { termination = finished.await() }
                finally {
                    closing.complete(CloseFrameInfo(null))
                    runCatching { engine.abort() }.onFailure { warn("websocket.engine.abort.failed") }
                    reader.cancel(); writer.cancel(); heartbeat.cancel(); stop.cancel(); closingDeadline.cancel()
                    business?.cancel()
                }
            }
        } finally {
            runCatching { engine.abort() }.onFailure { warn("websocket.engine.abort.failed") }
            inbound.cancel(); outbound.cancel()
            businessParent.cancel()
            // Publish after transport/queue cleanup, before joining business finally blocks.
            closedResult.complete(CloseResult(trigger, localRequest, sentClose, receivedClose, termination))
            val complete = withContext(NonCancellable) {
                withTimeoutOrNull(config.handlerShutdownMillis) { business?.join(); true } ?: false
            }
            if (!complete) warn("websocket.handler.shutdown.timeout")
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
            requestClose(CloseFrameInfo(1011, "Consumer stalled"), cause = CloseTrigger.CONSUMER_STALL)
        } catch (e: CancellationException) {
            if (!closing.isCompleted || !currentCoroutineContext().isActive) throw e
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
                    requestClose(CloseFrameInfo(1001, "Inbound idle"), cause = CloseTrigger.IDLE_TIMEOUT); return
                }
                val flushed = pingFlushedAt
                if (flushed != null && config.pongTimeoutMillis > 0 && time - flushed >= config.pongTimeoutMillis) {
                    requestClose(CloseFrameInfo(1001, "Pong timeout"), cause = CloseTrigger.PONG_TIMEOUT); return
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
