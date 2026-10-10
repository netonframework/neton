package neton.ws.spi

import kotlinx.coroutines.CoroutineDispatcher
import neton.core.http.adapter.HttpAdapter
import neton.core.http.upgrade.UpgradedConnection

/** Engine integration is experimental until the framework runtime conformance suite is complete. */
@RequiresOptIn(level = RequiresOptIn.Level.WARNING)
annotation class ExperimentalWebSocketEngineApi

/** This is NOT a declaration that the HTTP adapter already supports WebSocket routes. */
enum class WebSocketEngineCapability {
    MESSAGE_LIMITS,
    /** Reserve payload bytes before allocating/reassembling data; not an RSS or copy-overhead limit. */
    PREALLOCATION_BUDGET,
    REJECT_DATA,
    VALIDATED_DISCARD,
}

data class EngineLimits(
    val maxMessageBytes: Int = 1024 * 1024,
    val maxFrameBytes: Int = 1024 * 1024,
    val readBufferBytes: Int = 16 * 1024,
    val writeBufferBytes: Int = 16 * 1024,
    val maxWriteBufferBytes: Int = 2 * 1024 * 1024,
) {
    init {
        require(maxMessageBytes > 0 && maxFrameBytes > 0)
        require(readBufferBytes > 0 && writeBufferBytes >= 0)
        require(maxWriteBufferBytes > writeBufferBytes)
    }
}

/** Immutable handshake snapshot. Values preserve repeated headers for protocol validation. */
class HandshakeRequest(
    val method: String,
    val target: String,
    headers: Map<String, List<String>>,
    val httpVersion: String = "HTTP/1.1",
) {
    val headers: Map<String, List<String>> = headers.mapValues { it.value.toList() }
}

class HandshakeOffer(val subprotocol: String? = null)

/** Opaque negotiated state must only be consumed by the provider that produced it. */
interface Negotiated

sealed interface HandshakeResult {
    class Accepted(val headers: Map<String, List<String>>, val negotiated: Negotiated) : HandshakeResult
    class Rejected(val status: Int, val reason: String, val headers: Map<String, List<String>> = emptyMap()) : HandshakeResult
}

@ExperimentalWebSocketEngineApi
interface WebSocketEngineProvider {
    val name: String
    val capabilities: Set<WebSocketEngineCapability>
    fun supports(adapter: HttpAdapter): Boolean
    /** Protocol checks only. Origin/authentication/admission belong to the framework. */
    fun handshake(request: HandshakeRequest, offer: HandshakeOffer = HandshakeOffer()): HandshakeResult
    /**
     * Call on connection.executor, only after HTTP has flushed the upgrade response.
     * On failure the caller still owns connection; on success the engine owns it.
     */
    suspend fun open(connection: UpgradedConnection, negotiated: Negotiated, limits: EngineLimits): WebSocketEngineConnection
}

sealed interface WebSocketEngineEvent {
    data object DataRejected : WebSocketEngineEvent
    class Text(val text: String, val lease: BudgetLease = BudgetLease(UnlimitedByteBudget, 0)) : WebSocketEngineEvent
    class Binary(val bytes: ByteArray, val lease: BudgetLease = BudgetLease(UnlimitedByteBudget, 0)) : WebSocketEngineEvent
    class Pong(val bytes: ByteArray) : WebSocketEngineEvent
    class CloseReceived(val code: Int?, val reason: String) : WebSocketEngineEvent
}

/**
 * Low-level, executor-confined SPI, not a business session. One reader and one writer at a time.
 * The engine alone replies to peer Ping/Close. Writes return after flushing, not peer acknowledgement.
 * Inputs must not be mutated until a write returns or throws. Cancellation requires abort before reuse.
 */
@ExperimentalWebSocketEngineApi
interface WebSocketEngineConnection {
    val executor: CoroutineDispatcher
    /** Select before receiving. Unsupported policies must fail, never silently degrade. */
    fun configureInbound(policy: neton.ws.InboundPolicy) {
        require(policy == neton.ws.InboundPolicy.BACKPRESSURE) { "Inbound policy unsupported" }
    }
    suspend fun receive(): WebSocketEngineEvent?
    suspend fun receive(budget: ByteBudget): WebSocketEngineEvent? = error("Preallocation budget unsupported")
    fun discardData() = Unit
    suspend fun writeText(text: String)
    suspend fun writeBinary(bytes: ByteArray)
    suspend fun writePing(bytes: ByteArray)
    /** Initiate the handshake; the owner must keep receiving and enforce a close deadline. */
    suspend fun writeClose(code: Int? = 1000, reason: String = "")
    fun abort()
}
