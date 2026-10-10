@file:OptIn(neton.ws.spi.ExperimentalWebSocketEngineApi::class)

package neton.ws.conformance

import kotlinx.coroutines.*
import neton.ws.InboundPolicy
import neton.ws.spi.*

/** Wire operations must use an independent peer, not synthesize engine events. */
interface WebSocketEngineFixture {
    val engine: WebSocketEngineConnection
    suspend fun sendText(value: String)
    suspend fun sendPing(bytes: ByteArray)
    suspend fun receivePong(): ByteArray
    suspend fun sendEmptyClose()
    /** Write raw client bytes without waiting for a complete frame. */
    suspend fun sendRaw(bytes: ByteArray)
    fun close()
}

/**
 * Run every method on the engine executor with a fresh fixture. Declared capabilities
 * cannot be skipped. This checks the bridge; it does not replace Autobahn or runtime tests.
 */
abstract class WebSocketEngineConformanceSuite {
    abstract val capabilities: Set<WebSocketEngineCapability>
    abstract suspend fun open(limits: EngineLimits = EngineLimits()): WebSocketEngineFixture
    abstract fun recordSkipped(capability: WebSocketEngineCapability)

    private suspend fun fixture(limits: EngineLimits = EngineLimits(), block: suspend (WebSocketEngineFixture) -> Unit) {
        withTimeout(5000) {
            val f = open(limits)
            try { block(f) } finally { f.close() }
        }
    }

    suspend fun textAndEmptyClose() = fixture { f ->
        f.sendText("contract")
        val message = f.engine.receive() as? WebSocketEngineEvent.Text ?: throw AssertionError("Missing text")
        try { check(message.text == "contract") } finally { message.lease.release() }
        f.sendEmptyClose()
        val close = f.engine.receive() as? WebSocketEngineEvent.CloseReceived ?: throw AssertionError("Missing Close")
        check(close.code == null && close.reason.isEmpty()) { "Empty Close was rewritten" }
    }

    suspend fun automaticPong() = fixture { f ->
        coroutineScope {
            val reading = async { f.engine.receive() }
            f.sendPing(byteArrayOf(1, 3, 5))
            check(f.receivePong().contentEquals(byteArrayOf(1, 3, 5)))
            f.sendText("after")
            val message = reading.await() as? WebSocketEngineEvent.Text ?: throw AssertionError("Ping leaked or data missing")
            message.lease.release()
        }
    }

    suspend fun preallocationRefusal() {
        if (WebSocketEngineCapability.PREALLOCATION_BUDGET !in capabilities) {
            recordSkipped(WebSocketEngineCapability.PREALLOCATION_BUDGET); return
        }
        fixture { f ->
            var requested = 0L
            val budget = object : ByteBudget {
                override fun tryReserve(bytes: Long): Boolean { requested += bytes; return false }
                override fun release(bytes: Long) { check(bytes == 0L) { "Rejected reservation released" } }
            }
            f.sendRaw(byteArrayOf(0x82.toByte(), 0xfe.toByte(), 0x10, 0, 0, 0, 0, 0))
            try { f.engine.receive(budget); throw AssertionError("Reservation ignored") }
            catch (_: WebSocketCapacityException) { check(requested == 4096L) }
        }
    }

    suspend fun rejectBeforePayload() {
        if (WebSocketEngineCapability.REJECT_DATA !in capabilities) {
            recordSkipped(WebSocketEngineCapability.REJECT_DATA); return
        }
        fixture { f ->
            f.engine.configureInbound(InboundPolicy.REJECT_DATA)
            f.sendRaw(byteArrayOf(0x82.toByte(), 0xfe.toByte(), 0x10, 0, 0, 0, 0, 0))
            check(f.engine.receive() === WebSocketEngineEvent.DataRejected)
        }
    }

    suspend fun validatedDiscardKeepsControls() {
        if (WebSocketEngineCapability.VALIDATED_DISCARD !in capabilities) {
            recordSkipped(WebSocketEngineCapability.VALIDATED_DISCARD); return
        }
        fixture { f ->
            f.engine.configureInbound(InboundPolicy.DISCARD_DATA)
            coroutineScope {
                val reading = async { f.engine.receive() }
                repeat(64) { f.sendText("discard") }
                f.sendPing(byteArrayOf(7))
                check(f.receivePong().contentEquals(byteArrayOf(7)))
                f.sendEmptyClose()
                check(reading.await() is WebSocketEngineEvent.CloseReceived)
            }
        }
    }

    suspend fun abortReleasesPendingRead() = fixture { f ->
        coroutineScope {
            val reading = async(start = CoroutineStart.UNDISPATCHED) { runCatching { f.engine.receive() } }
            f.engine.abort()
            reading.await()
            f.engine.abort()
        }
    }

    suspend fun frameLimitBeforePayload() {
        if (WebSocketEngineCapability.MESSAGE_LIMITS !in capabilities) {
            recordSkipped(WebSocketEngineCapability.MESSAGE_LIMITS); return
        }
        fixture(EngineLimits(maxMessageBytes = 16, maxFrameBytes = 16)) { f ->
            f.sendRaw(byteArrayOf(0x82.toByte(), 0x91.toByte(), 0, 0, 0, 0))
            val error = runCatching { f.engine.receive() }.exceptionOrNull()
            if (error is CancellationException) throw error
            check(error != null) { "Oversized frame was not rejected before its payload arrived" }
        }
    }

    suspend fun discardRejectsInvalidUtf8() {
        if (WebSocketEngineCapability.VALIDATED_DISCARD !in capabilities) {
            recordSkipped(WebSocketEngineCapability.VALIDATED_DISCARD); return
        }
        fixture { f ->
            f.engine.configureInbound(InboundPolicy.DISCARD_DATA)
            f.sendRaw(byteArrayOf(0x81.toByte(), 0x81.toByte(), 0, 0, 0, 0, 0xc0.toByte()))
            val error = runCatching { f.engine.receive() }.exceptionOrNull()
            if (error is CancellationException) throw error
            check(error != null) { "Invalid UTF-8 was silently discarded" }
        }
    }
}
