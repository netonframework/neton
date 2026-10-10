@file:OptIn(neton.ws.spi.ExperimentalWebSocketEngineApi::class)
package neton.ws

import kotlinx.coroutines.*
import neton.http.engine.default.DefaultUpgradedConnection
import neton.io.bytes.Buffer
import neton.io.bytes.Bytes
import neton.io.core.memoryStreamPair
import neton.io.net.runReactor
import neton.websocket.*
import neton.ws.conformance.*
import neton.ws.engine.default.DefaultWebSocketEngineProvider
import neton.ws.spi.*
import kotlin.coroutines.ContinuationInterceptor
import kotlin.test.*

class EngineConformanceTest {
    private val suite = object : WebSocketEngineConformanceSuite() {
        private val provider = DefaultWebSocketEngineProvider()
        override val capabilities get() = provider.capabilities
        override fun recordSkipped(capability: WebSocketEngineCapability) { error("Default provider must implement $capability") }
        override suspend fun open(limits: EngineLimits): WebSocketEngineFixture {
            val (server, peer) = memoryStreamPair(4096)
            val request = HandshakeRequest("GET", "/", mapOf("Host" to listOf("localhost"),
                "Connection" to listOf("Upgrade"), "Upgrade" to listOf("websocket"),
                "Sec-WebSocket-Version" to listOf("13"), "Sec-WebSocket-Key" to listOf("dGhlIHNhbXBsZSBub25jZQ==")))
            val state = assertIs<HandshakeResult.Accepted>(provider.handshake(request)).negotiated
            val dispatcher = currentCoroutineContext()[ContinuationInterceptor] as CoroutineDispatcher
            val engine = provider.open(DefaultUpgradedConnection(server, dispatcher), state, limits)
            val client = neton.websocket.WebSocket.fromRawStream(peer, Role.Client)
            return object : WebSocketEngineFixture {
                override val engine = engine
                override suspend fun sendText(value: String) = client.send(Message.Text(value))
                override suspend fun sendPing(bytes: ByteArray) = client.send(Message.Ping(Bytes.wrap(bytes)))
                override suspend fun receivePong() = assertIs<Message.Pong>(client.receive()).data.toByteArray()
                override suspend fun sendEmptyClose() = client.close(null)
                override suspend fun sendRaw(bytes: ByteArray) {
                    val buffer = Buffer().apply { writeBytes(bytes) }
                    while (!buffer.isEmpty) peer.write(buffer)
                    peer.flush()
                }
                override fun close() { try { engine.abort() } finally { client.abort() } }
            }
        }
    }
    @Test fun textAndEmptyClose() = runReactor { suite.textAndEmptyClose() }
    @Test fun automaticPong() = runReactor { suite.automaticPong() }
    @Test fun preallocationRefusal() = runReactor { suite.preallocationRefusal() }
    @Test fun rejectBeforePayload() = runReactor { suite.rejectBeforePayload() }
    @Test fun validatedDiscardKeepsControls() = runReactor { suite.validatedDiscardKeepsControls() }
    @Test fun abortReleasesPendingRead() = runReactor { suite.abortReleasesPendingRead() }
    @Test fun frameLimitBeforePayload() = runReactor { suite.frameLimitBeforePayload() }
    @Test fun discardRejectsInvalidUtf8() = runReactor { suite.discardRejectsInvalidUtf8() }
}
