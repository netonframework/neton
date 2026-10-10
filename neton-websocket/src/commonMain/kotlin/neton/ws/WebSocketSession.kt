package neton.ws

import kotlinx.coroutines.flow.Flow
import neton.core.interfaces.Identity

sealed interface WebSocketMessage {
    data class Text(val value: String) : WebSocketMessage
    /** Borrowed until send returns; send reserves capacity before copying. */
    class Binary(val bytes: ByteArray) : WebSocketMessage
}

data class CloseFrameInfo(val code: Int?, val reason: String = "")
enum class CloseTrigger {
    LOCAL_NORMAL, APPLICATION, PEER, SERVER_SHUTDOWN, HANDLER_FAILURE,
    PONG_TIMEOUT, IDLE_TIMEOUT, CONSUMER_STALL, RESOURCE_PRESSURE, PROTOCOL_ERROR, TRANSPORT_FAILURE,
}
enum class CloseTermination { HANDSHAKE_COMPLETE, PEER_EOF, TRANSPORT_FAILURE, WRITE_TIMEOUT, CLOSE_TIMEOUT, FORCED_ABORT }
data class CloseResult(
    val trigger: CloseTrigger,
    val localRequest: CloseFrameInfo?,
    val sent: CloseFrameInfo?,
    val received: CloseFrameInfo?,
    val termination: CloseTermination,
) {
    val handshakeComplete: Boolean get() = sent != null && received != null
}

interface HandshakeInfo {
    val path: String
    val peerAddress: String
    val isSecure: Boolean
    val subprotocol: String?
    val identity: Identity?
    fun pathParam(name: String): String?
    fun queryParams(name: String): List<String>
    fun headerValues(name: String): List<String>
}

fun interface WebSocketHandler {
    suspend fun handle(session: WebSocketSession)
}

enum class InboundPolicy { BACKPRESSURE, REJECT_DATA, DISCARD_DATA }

/** A closed connection is an operation failure, not cancellation of the caller's coroutine. */
class WebSocketClosedException : IllegalStateException("WebSocket is closing or closed")

interface WebSocketSession {
    /** Unique connection identifier, not a credential or a user identifier. */
    val sessionId: String
    val handshake: HandshakeInfo
    val subprotocol: String? get() = handshake.subprotocol
    /** One collector at a time; subsequent collection resumes unread messages, without replay. */
    val incoming: Flow<WebSocketMessage>
    /** Cancelling an observer does not cancel the connection or another observer. */
    suspend fun awaitClosed(): CloseResult
    /**
     * Waits for payload capacity; returns after FIFO acceptance, not network delivery.
     * Cancellation after acceptance does not retract a message. Do not blindly retry
     * a cancelled send; application acknowledgements are needed for delivery guarantees.
     */
    suspend fun send(message: WebSocketMessage)
    suspend fun send(text: String) = send(WebSocketMessage.Text(text))
    /** Normal local close drains accepted messages before Close; transport failures may abort. */
    suspend fun close(code: Int = 1000, reason: String = "")
}

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
annotation class WebSocket(val value: String = "", val subprotocols: Array<String> = [], val inboundPolicy: InboundPolicy = InboundPolicy.BACKPRESSURE)
