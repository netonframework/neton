package neton.ws

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.Flow
import neton.core.http.HttpContext

sealed interface WebSocketMessage {
    data class Text(val value: String) : WebSocketMessage
    /** Borrowed until send returns; send reserves capacity before copying. */
    class Binary(val bytes: ByteArray) : WebSocketMessage
}

data class WebSocketClose(val code: Int, val reason: String = "")

/** A closed connection is an operation failure, not cancellation of the caller's coroutine. */
class WebSocketClosedException : IllegalStateException("WebSocket is closing or closed")

interface WebSocketSession {
    val context: HttpContext
    val subprotocol: String?
    /** Single collector; protocol control frames are handled independently. */
    val incoming: Flow<WebSocketMessage>
    val closed: Deferred<WebSocketClose>
    /** Waits for payload capacity; returns after FIFO acceptance, not network delivery. */
    suspend fun send(message: WebSocketMessage)
    suspend fun send(text: String) = send(WebSocketMessage.Text(text))
    /** Normal local close drains accepted messages before Close; transport failures may abort. */
    suspend fun close(code: Int = 1000, reason: String = "")
}

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
annotation class WebSocket(val value: String = "", val subprotocols: Array<String> = [])
