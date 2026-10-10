@file:OptIn(neton.ws.spi.ExperimentalWebSocketEngineApi::class)

package neton.ws.engine.default

import kotlinx.coroutines.currentCoroutineContext
import neton.core.http.adapter.HttpAdapter
import neton.core.http.upgrade.UpgradedConnection
import neton.http.Request
import neton.http.header.HeaderValue
import neton.http.engine.default.DefaultHttpAdapter
import neton.io.bytes.Bytes
import neton.io.core.IoStream
import neton.websocket.Message
import neton.websocket.Role
import neton.websocket.WebSocket
import neton.websocket.WebSocketConfig
import neton.websocket.frame.CloseCode
import neton.websocket.frame.CloseFrame
import neton.websocket.handshake.createResponse
import neton.ws.spi.*
import kotlin.coroutines.ContinuationInterceptor

/** Built in, not discovered by classpath scanning. Protocol policy remains outside this bridge. */
class DefaultWebSocketEngineProvider : WebSocketEngineProvider {
    override val name = "NetonStream"
    override val capabilities = WebSocketEngineCapability.entries.toSet()
    override fun supports(adapter: HttpAdapter) = adapter is DefaultHttpAdapter

    private class State(val owner: DefaultWebSocketEngineProvider) : Negotiated

    override fun handshake(request: HandshakeRequest, offer: HandshakeOffer): HandshakeResult {
        fun values(name: String) = request.headers.entries
            .filter { it.key.equals(name, ignoreCase = true) }.flatMap { it.value }
        fun invalid(reason: String) = HandshakeResult.Rejected(400, reason)
        if (request.method != "GET") return HandshakeResult.Rejected(405, "GET required", mapOf("Allow" to listOf("GET")))
        if (request.httpVersion != "HTTP/1.1") return invalid("HTTP/1.1 required")
        if (values("Sec-WebSocket-Version") != listOf("13")) {
            return HandshakeResult.Rejected(426, "WebSocket version 13 required", mapOf("Sec-WebSocket-Version" to listOf("13")))
        }
        if (values("Sec-WebSocket-Key").size != 1) return invalid("Exactly one Sec-WebSocket-Key required")
        val offered = values("Sec-WebSocket-Protocol").flatMap { it.split(',') }.map { it.trim() }
        if (offered.any { !isToken(it) } || offered.distinct().size != offered.size) return invalid("Invalid subprotocol offer")
        if (offer.subprotocol != null && (!isToken(offer.subprotocol) || offer.subprotocol !in offered)) {
            return invalid("Selected subprotocol was not offered")
        }
        return try {
            val nativeRequest = Request.builder().method("GET").uri(request.target).body(Unit)
            for ((name, values) in request.headers) {
                for (value in values) nativeRequest.headers.append(name, HeaderValue.fromStr(value))
            }
            val response = createResponse(nativeRequest)
            val headers = mutableMapOf<String, List<String>>()
            response.headers.forEach { name, value -> headers[name.asStr()] = listOf(value.toStr()) }
            offer.subprotocol?.let { headers["sec-websocket-protocol"] = listOf(it) }
            HandshakeResult.Accepted(headers, State(this))
        } catch (_: IllegalArgumentException) {
            invalid("Invalid handshake")
        } catch (_: neton.websocket.WebSocketException) {
            invalid("Invalid handshake")
        } catch (_: neton.http.HttpError) {
            invalid("Invalid handshake")
        }
    }

    override suspend fun open(
        connection: UpgradedConnection,
        negotiated: Negotiated,
        limits: EngineLimits,
    ): WebSocketEngineConnection {
        require(negotiated is State && negotiated.owner === this) { "Negotiation belongs to another provider" }
        check(currentCoroutineContext()[ContinuationInterceptor] === connection.executor) { "Open must run on the connection executor" }
        val stream = requireNotNull(connection.unwrap(IoStream::class)) { "Expected an IoStream transport" }
        // The stream itself retains the HTTP parser's unread prefix; do not unwrap it a second time.
        val socket = WebSocket.fromRawStream(stream, Role.Server, WebSocketConfig(
            readBufferSize = limits.readBufferBytes,
            writeBufferSize = limits.writeBufferBytes,
            maxWriteBufferSize = limits.maxWriteBufferBytes,
            maxMessageSize = limits.maxMessageBytes,
            maxFrameSize = limits.maxFrameBytes,
            sendCloseOnProtocolError = true,
        ))
        return Connection(connection, socket)
    }

    private class Connection(
        private val transport: UpgradedConnection,
        private val socket: WebSocket,
    ) : WebSocketEngineConnection {
        override val executor get() = transport.executor
        private var aborted = false
        private var budget: ByteBudget = UnlimitedByteBudget
        private var reserved = 0L

        override fun configureInbound(policy: neton.ws.InboundPolicy) {
            socket.setInboundDataPolicy(when (policy) {
                neton.ws.InboundPolicy.BACKPRESSURE -> neton.websocket.InboundDataPolicy.DELIVER
                neton.ws.InboundPolicy.REJECT_DATA -> neton.websocket.InboundDataPolicy.REJECT
                neton.ws.InboundPolicy.DISCARD_DATA -> neton.websocket.InboundDataPolicy.DISCARD
            })
        }

        init {
            socket.setInboundAdmission { bytes ->
                // Count payload once across fragments. Copies, representation and capacity
                // overhead are not a second message and are outside this payload budget.
                val charge = bytes.toLong()
                if (!budget.tryReserve(charge)) throw WebSocketCapacityException()
                reserved += charge
            }
        }

        private fun lease(): BudgetLease = BudgetLease(budget, reserved).also { reserved = 0 }

        override fun discardData() {
            socket.discardData()
            budget.release(reserved)
            reserved = 0
        }

        private suspend fun checkExecutor() {
            check(currentCoroutineContext()[ContinuationInterceptor] === executor) { "WebSocket engine access on wrong executor" }
        }

        override suspend fun receive(): WebSocketEngineEvent? {
            return receive(UnlimitedByteBudget)
        }

        override suspend fun receive(budget: ByteBudget): WebSocketEngineEvent? {
            checkExecutor()
            check(reserved == 0L || this.budget === budget) { "Cannot change an in-progress message budget" }
            this.budget = budget
            var controls = 0
            while (true) {
                val message = try { socket.receive() ?: return null }
                    catch (_: neton.websocket.InboundDataRejectedException) {
                        discardData()
                        return WebSocketEngineEvent.DataRejected
                    }
                when (message) {
                    is Message.Text -> return WebSocketEngineEvent.Text(message.text.asString(), lease())
                    is Message.Binary -> return WebSocketEngineEvent.Binary(message.data.toByteArray(), lease())
                    is Message.Pong -> return WebSocketEngineEvent.Pong(message.data.toByteArray())
                    is Message.Close -> return WebSocketEngineEvent.CloseReceived(message.frame?.code?.code, message.frame?.reason?.asString() ?: "")
                    is Message.Ping -> Unit // The protocol engine, not the framework, supplies the Pong.
                    is Message.Frame -> error("The protocol engine returned a raw frame")
                }
                if (++controls % 32 == 0) kotlinx.coroutines.yield()
            }
        }

        override suspend fun writeText(text: String) {
            checkExecutor()
            socket.send(Message.Text(text))
        }
        override suspend fun writeBinary(bytes: ByteArray) {
            checkExecutor()
            socket.send(Message.Binary(Bytes.wrap(bytes)))
        }
        override suspend fun writePing(bytes: ByteArray) {
            checkExecutor()
            require(bytes.size <= 125) { "Ping payload exceeds 125 bytes" }
            socket.send(Message.Ping(Bytes.wrap(bytes)))
        }
        override suspend fun writeClose(code: Int?, reason: String) {
            checkExecutor()
            if (code == null) {
                require(reason.isEmpty()) { "An empty Close cannot have a reason" }
                socket.close(null)
                return
            }
            val closeCode = CloseCode.from(code)
            require(closeCode.isAllowed) { "Invalid wire close code: $code" }
            require(reason.encodeToByteArray().size <= 123) { "Close reason exceeds 123 bytes" }
            socket.close(CloseFrame(closeCode, reason))
        }
        override fun abort() {
            if (aborted) return
            aborted = true
            try { socket.abort() } finally {
                budget.release(reserved)
                reserved = 0
                transport.close()
            }
        }
    }
}

private fun isToken(value: String): Boolean = value.isNotEmpty() && value.all {
    it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "!#$%&'*+-.^_`|~"
}
