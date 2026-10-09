@file:OptIn(neton.ws.spi.ExperimentalWebSocketEngineApi::class, kotlinx.cinterop.ExperimentalForeignApi::class)
package neton.ws

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import neton.core.component.NetonContext
import neton.core.http.adapter.*
import neton.core.interfaces.*
import neton.http.HttpComponent
import neton.http.engine.default.*
import neton.io.bytes.Buffer
import neton.io.core.IoStream
import neton.io.net.*
import neton.websocket.Message
import neton.websocket.client
import neton.openssl.TlsContext
import neton.openssl.PeerIdentity
import neton.tls.tlsConnect
import kotlin.test.*

class ServerIntegrationTest {
    private class Routes : RequestEngine {
        val routes = mutableListOf<RouteDefinition>()
        override fun registerRoute(route: RouteDefinition) { routes += route }
        override fun getRoutes() = routes.toList()
    }
    private suspend fun start(scope: CoroutineScope, config: WebSocketConfig, tls: TlsSettings? = null, handler: suspend (WebSocketSession) -> Unit): Server {
        val temporary = listen("127.0.0.1", 0)
        val port = temporary.localAddress.port
        temporary.close()
        val adapter = DefaultHttpAdapter(HttpServerConfig(port, timeout = 1000, maxConnections = 1, tls = tls),
            DefaultHttpOptions(host = "127.0.0.1", reactors = 1))
        val ctx = NetonContext(emptyArray())
        val routes = Routes().apply { webSocket("/echo/{room}", handler = handler) }
        ctx.bind(HttpAdapter::class, adapter)
        ctx.bind(RequestEngine::class, routes)
        ctx.bind(ConfiguredRouteGroups(emptySet()))
        val component = WebSocketComponent()
        component.init(ctx, config)
        component.prepare(ctx)
        HttpComponent().prepare(ctx)
        val ready = CompletableDeferred<Unit>()
        val job = scope.launch(Dispatchers.Default) { adapter.start(ctx) { ready.complete(Unit) } }
        job.invokeOnCompletion { if (it != null) ready.completeExceptionally(it) }
        withTimeout(10000) { ready.await() }
        return Server(port, adapter, component, ctx, job)
    }
    private class Server(val port: Int, val adapter: DefaultHttpAdapter, val component: WebSocketComponent, val ctx: NetonContext, val job: Job) {
        suspend fun stop() { component.stop(ctx); adapter.stop(); job.join() }
    }
    private fun config() = WebSocketConfig().apply {
        pingIntervalMillis = 0; closeTimeoutMillis = 200; handlerShutdownMillis = 200
    }

    @Test fun verifiedTlsUpgradeEcho() = runReactor {
        withTimeout(15000) {
            val identity = websocketTestIdentity()
            val cert = websocketTempFile("cert", identity.first)
            val key = websocketTempFile("key", identity.second)
            try {
                val server = start(this, config(), TlsSettings(cert, key)) { session ->
                    session.incoming.collect { session.send(it) }
                }
                val trust = TlsContext(false, identity.first, alpnProtocols = listOf("http/1.1"))
                try {
                    val stream = tlsConnect(connect("127.0.0.1", server.port), trust, PeerIdentity.Ip("127.0.0.1"))
                    val (socket, response) = client("wss://127.0.0.1:${server.port}/echo/tls", stream)
                    try {
                        assertEquals(101, response.status.asU16())
                        socket.send(Message.Text("secure"))
                        assertEquals("secure", assertIs<Message.Text>(socket.receive()).text.asString())
                    } finally { socket.abort() }
                } finally { trust.close(); server.stop() }
            } finally { platform.posix.unlink(cert); platform.posix.unlink(key) }
        }
    }

    @Test fun realUpgradeEchoAndHttpQuotaReleased() = runReactor {
        withTimeout(15000) {
            val server = start(this, config()) { session ->
                session.incoming.collect { message ->
                    when (message) {
                        is WebSocketMessage.Text -> session.send(session.context.request.pathParam("room") + ":" + message.value)
                        is WebSocketMessage.Binary -> session.send(message)
                    }
                }
            }
            try {
                val (one, response) = client("ws://127.0.0.1:${server.port}/echo/a", connect("127.0.0.1", server.port))
                assertEquals(101, response.status.asU16())
                // HTTP maxConnections is one, but upgraded sessions must no longer hold that request slot.
                val (two, _) = client("ws://127.0.0.1:${server.port}/echo/b", connect("127.0.0.1", server.port))
                try {
                    one.send(Message.Text("hello"))
                    assertEquals("a:hello", assertIs<Message.Text>(one.receive()).text.asString())
                    two.send(Message.Binary(neton.io.bytes.Bytes.wrap(byteArrayOf(1, 2))))
                    assertContentEquals(byteArrayOf(1, 2), assertIs<Message.Binary>(two.receive()).data.toByteArray())
                    val stopping = async { server.component.stop(server.ctx) }
                    assertEquals(1001, assertIs<Message.Close>(one.receive()).frame?.code?.code)
                    assertEquals(1001, assertIs<Message.Close>(two.receive()).frame?.code?.code)
                    stopping.await()
                } finally { one.abort(); two.abort() }
            } finally { server.stop() }
        }
    }

    @Test fun originRejectedAndMissingUpgradeReturns426() = runReactor {
        withTimeout(15000) {
            val server = start(this, config()) { error("must not run") }
            try {
                val rejected = request(server.port, "Origin: https://evil.example\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n")
                assertTrue(rejected.startsWith("HTTP/1.1 403"), rejected)
                assertTrue(request(server.port, "Connection: close\r\n").startsWith("HTTP/1.1 426"))
            } finally { server.stop() }
        }
    }

    @Test fun prefetchedFirstFrameAndHandlerReturnClose() = runReactor {
        withTimeout(15000) {
            val received = CompletableDeferred<String>()
            val server = start(this, config()) { session ->
                session.incoming.collect { msg ->
                    received.complete(assertIs<WebSocketMessage.Text>(msg).value)
                    session.close()
                }
            }
            val stream = connect("127.0.0.1", server.port)
            try {
                val data = Buffer()
                data.writeBytes(("GET /echo/x HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n\r\n").encodeToByteArray())
                data.writeBytes(byteArrayOf(0x81.toByte(), 0x81.toByte(), 0, 0, 0, 0, 120))
                while (!data.isEmpty) stream.write(data)
                stream.flush()
                assertEquals("x", received.await())
            } finally { stream.close(); server.stop() }
        }
    }

    private suspend fun request(port: Int, headers: String): String {
        val stream = connect("127.0.0.1", port)
        try {
            val out = Buffer()
            out.writeBytes("GET /echo/a HTTP/1.1\r\nHost: localhost\r\n${headers}\r\n".encodeToByteArray())
            while (!out.isEmpty) stream.write(out)
            stream.flush()
            val input = Buffer()
            while (!input.peekAll().decodeToString().contains("\r\n\r\n")) {
                if (stream.read(input) < 0) break
            }
            return input.readAll().decodeToString()
        } finally { stream.close() }
    }
}
