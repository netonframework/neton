@file:OptIn(neton.ws.spi.ExperimentalWebSocketEngineApi::class)

package neton.ws

import kotlinx.coroutines.*
import neton.core.Neton
import neton.core.component.NetonContext
import neton.core.http.adapter.HttpAdapter
import neton.core.http.adapter.HttpCapability
import neton.core.http.adapter.HttpServerConfig
import neton.core.http.upgrade.UpgradedConnection
import neton.http.engine.default.DefaultHttpAdapter
import neton.http.engine.default.DefaultUpgradedConnection
import neton.io.core.memoryStreamPair
import neton.io.net.runReactor
import neton.websocket.Message
import neton.websocket.Role
import neton.websocket.WebSocket
import neton.ws.engine.default.DefaultWebSocketEngineProvider
import neton.ws.spi.*
import kotlin.coroutines.ContinuationInterceptor
import kotlin.test.*

class DefaultProviderTest {
    private fun request(extra: Map<String, List<String>> = emptyMap()) = HandshakeRequest(
        "GET", "/chat", mapOf(
            "Host" to listOf("localhost"),
            "Connection" to listOf("Upgrade"),
            "Upgrade" to listOf("websocket"),
            "Sec-WebSocket-Version" to listOf("13"),
            "Sec-WebSocket-Key" to listOf("dGhlIHNhbXBsZSBub25jZQ=="),
        ) + extra,
    )

    @Test fun builtInProviderIsInstalledWithoutAnotherModule(): Unit = runBlocking {
        Neton.LaunchBuilder().websocket { }
        val ctx = NetonContext(emptyArray())
        ctx.bind(HttpAdapter::class, DefaultHttpAdapter(HttpServerConfig(port = 0)))
        val component = WebSocketComponent()
        component.init(ctx, component.defaultConfig())
        component.prepare(ctx)
        assertIs<DefaultWebSocketEngineProvider>(ctx.get<WebSocketEngineProvider>())
        assertFailsWith<IllegalStateException> { component.init(ctx, component.defaultConfig()) }
    }

    @Test fun explicitProviderWinsAndStateIsPerApplication() = runBlocking {
        val custom = object : WebSocketEngineProvider {
            override val name = "custom"
            override val capabilities = WebSocketEngineCapability.entries.toSet()
            override fun supports(adapter: HttpAdapter) = true
            override fun handshake(request: HandshakeRequest, offer: HandshakeOffer) = HandshakeResult.Rejected(403, "test")
            override suspend fun open(connection: UpgradedConnection, negotiated: Negotiated, limits: EngineLimits): WebSocketEngineConnection = error("not used")
        }
        Neton.LaunchBuilder().websocket({ custom }) { }
        val ctx = NetonContext(emptyArray())
        ctx.bind(HttpAdapter::class, DefaultHttpAdapter(HttpServerConfig(port = 0)))
        val component = WebSocketComponent { custom }
        component.init(ctx, component.defaultConfig())
        component.prepare(ctx)
        assertSame(custom, ctx.get<WebSocketEngineProvider>())
        val other = NetonContext(emptyArray())
        WebSocketComponent().init(other, WebSocketConfig())
        assertNotSame(custom, other.get<WebSocketEngineProvider>())
    }

    @Test fun incompatibleAdapterFailsAtPrepare(): Unit = runBlocking {
        val ctx = NetonContext(emptyArray())
        ctx.bind(HttpAdapter::class, object : HttpAdapter {
            override val capabilities = emptySet<HttpCapability>()
            override suspend fun start(ctx: NetonContext, onStarted: (suspend (Long) -> Unit)?) = Unit
            override suspend fun stop() = Unit
            override fun port() = 0
        })
        val component = WebSocketComponent()
        component.init(ctx, component.defaultConfig())
        assertFailsWith<IllegalStateException> { component.prepare(ctx) }
    }

    @Test fun unsupportedBudgetContractCannotSilentlyPass(): Unit = runBlocking {
        val ctx = NetonContext(emptyArray())
        ctx.bind(HttpAdapter::class, DefaultHttpAdapter(HttpServerConfig(port = 0)))
        val component = WebSocketComponent { object : WebSocketEngineProvider by DefaultWebSocketEngineProvider() {
            override val capabilities = setOf(WebSocketEngineCapability.MESSAGE_LIMITS)
        } }
        component.init(ctx, WebSocketConfig().apply {
            requiredCapabilities = setOf(WebSocketEngineCapability.PREALLOCATION_BUDGET)
        })
        assertFailsWith<IllegalStateException> { component.prepare(ctx) }
    }

    @Test fun handshakeAndSubprotocolAreValidated() {
        val provider = DefaultWebSocketEngineProvider()
        val accepted = assertIs<HandshakeResult.Accepted>(provider.handshake(request()))
        assertEquals(listOf("s3pPLMBiTxaQ9kYGzzhZRbK+xOo="), accepted.headers["sec-websocket-accept"])
        val selected = assertIs<HandshakeResult.Accepted>(provider.handshake(
            request(mapOf("Sec-WebSocket-Protocol" to listOf("chat, events"))), HandshakeOffer("chat"),
        ))
        assertEquals(listOf("chat"), selected.headers["sec-websocket-protocol"])
        assertIs<HandshakeResult.Rejected>(provider.handshake(request(), HandshakeOffer("not-offered")))
        assertIs<HandshakeResult.Rejected>(provider.handshake(request(mapOf("sec-websocket-key" to listOf("duplicate")))))
        assertEquals(426, assertIs<HandshakeResult.Rejected>(provider.handshake(request(mapOf("Sec-WebSocket-Version" to listOf("12"))))).status)
    }

    @Test fun optionalInboundPolicyMustBeSupportedAtRouteValidation(): Unit = runBlocking {
        val ctx = NetonContext(emptyArray())
        ctx.bind(HttpAdapter::class, DefaultHttpAdapter(HttpServerConfig(port = 0)))
        val component = WebSocketComponent { object : WebSocketEngineProvider by DefaultWebSocketEngineProvider() {
            override val capabilities = setOf(WebSocketEngineCapability.MESSAGE_LIMITS, WebSocketEngineCapability.PREALLOCATION_BUDGET)
        } }
        component.init(ctx, WebSocketConfig())
        component.prepare(ctx)
        webSocketEndpoint { }.validate(ctx)
        assertFailsWith<IllegalStateException> { webSocketEndpoint(inboundPolicy = InboundPolicy.DISCARD_DATA) { }.validate(ctx) }
        assertFailsWith<IllegalStateException> { webSocketEndpoint(inboundPolicy = InboundPolicy.REJECT_DATA) { }.validate(ctx) }
    }

    @Test fun realProtocolTextBinaryPingAndClose() = runReactor {
        withTimeout(5_000) {
            val (clientStream, serverStream) = memoryStreamPair(4096)
            val executor = currentCoroutineContext()[ContinuationInterceptor] as CoroutineDispatcher
            val provider = DefaultWebSocketEngineProvider()
            val negotiation = assertIs<HandshakeResult.Accepted>(provider.handshake(request())).negotiated
            val engine = provider.open(DefaultUpgradedConnection(serverStream, executor), negotiation, EngineLimits())
            val client = WebSocket.fromRawStream(clientStream, Role.Client)
            try {
                client.send(Message.Text("hello"))
                assertEquals("hello", assertIs<WebSocketEngineEvent.Text>(engine.receive()).text)
                engine.writeBinary(byteArrayOf(1, 2, 3))
                assertContentEquals(byteArrayOf(1, 2, 3), assertIs<Message.Binary>(client.receive()).data.toByteArray())
                coroutineScope {
                    val reader = async { engine.receive() }
                    client.send(Message.Ping(neton.io.bytes.Bytes.wrap(byteArrayOf(7))))
                    assertContentEquals(byteArrayOf(7), assertIs<Message.Pong>(client.receive()).data.toByteArray())
                    client.send(Message.Text("after-ping"))
                    assertEquals("after-ping", assertIs<WebSocketEngineEvent.Text>(reader.await()).text)
                }
                engine.writePing(byteArrayOf(9))
                assertIs<Message.Ping>(client.receive())
                client.flush()
                assertContentEquals(byteArrayOf(9), assertIs<WebSocketEngineEvent.Pong>(engine.receive()).bytes)
                coroutineScope {
                    val reader = async { engine.receive() }
                    client.close()
                    assertEquals(1000, assertIs<WebSocketEngineEvent.CloseReceived>(reader.await()).code ?: 1000)
                }
            } finally {
                engine.abort()
                engine.abort()
                client.abort()
            }
        }
    }

    @Test fun rejectsForeignNegotiationAndInvalidLimits() = runReactor {
        val provider = DefaultWebSocketEngineProvider()
        val other = DefaultWebSocketEngineProvider()
        val (a, b) = memoryStreamPair()
        val executor = currentCoroutineContext()[ContinuationInterceptor] as CoroutineDispatcher
        try {
            val negotiated = assertIs<HandshakeResult.Accepted>(other.handshake(request())).negotiated
            assertFailsWith<IllegalArgumentException> {
                provider.open(DefaultUpgradedConnection(a, executor), negotiated, EngineLimits())
            }
        } finally { a.close(); b.close() }
        assertFailsWith<IllegalArgumentException> { EngineLimits(maxMessageBytes = 0) }
    }

    @Test fun abortWakesPendingReadAndRejectsWrongExecutor() = runReactor {
        withTimeout(5_000) {
            val provider = DefaultWebSocketEngineProvider()
            val negotiated = assertIs<HandshakeResult.Accepted>(provider.handshake(request())).negotiated
            val (a, b) = memoryStreamPair()
            val executor = currentCoroutineContext()[ContinuationInterceptor] as CoroutineDispatcher
            val engine = provider.open(DefaultUpgradedConnection(a, executor), negotiated, EngineLimits())
            try {
                withContext(Dispatchers.Default) {
                    assertFailsWith<IllegalStateException> { engine.writeText("wrong thread") }
                }
                assertFailsWith<IllegalArgumentException> { engine.writeClose(1006) }
                assertFailsWith<IllegalArgumentException> { engine.writeClose(1000, "a".repeat(124)) }
                assertFailsWith<IllegalArgumentException> { engine.writePing(ByteArray(126)) }
                coroutineScope {
                    val pending = async(start = CoroutineStart.UNDISPATCHED) { runCatching { engine.receive() } }
                    engine.abort()
                    pending.await() // An exception is allowed; a permanently suspended read is not.
                }
            } finally { engine.abort(); b.close() }
        }
    }

    @Test fun unreadHttpPrefixIsPreserved() = runReactor {
        withTimeout(5_000) {
            val provider = DefaultWebSocketEngineProvider()
            val negotiated = assertIs<HandshakeResult.Accepted>(provider.handshake(request())).negotiated
            val (a, b) = memoryStreamPair()
            // Masked client text frame "x" using a zero mask, already read by HTTP.
            val prefix = byteArrayOf(0x81.toByte(), 0x81.toByte(), 0, 0, 0, 0, 'x'.code.toByte())
            val prefixed = object : neton.io.core.IoStream by a {
                var first = true
                override suspend fun read(dst: neton.io.bytes.Buffer): Int {
                    if (!first) return a.read(dst)
                    first = false
                    dst.writeBytes(prefix)
                    return prefix.size
                }
            }
            val executor = currentCoroutineContext()[ContinuationInterceptor] as CoroutineDispatcher
            val engine = provider.open(DefaultUpgradedConnection(prefixed, executor), negotiated, EngineLimits())
            try { assertEquals("x", assertIs<WebSocketEngineEvent.Text>(engine.receive()).text) }
            finally { engine.abort(); b.close() }
        }
    }

    @Test fun payloadReservationRefusedBeforeWaitingForPayload() = runReactor {
        withTimeout(3000) {
            val provider = DefaultWebSocketEngineProvider()
            val negotiated = assertIs<HandshakeResult.Accepted>(provider.handshake(request())).negotiated
            val (server, peer) = memoryStreamPair()
            val executor = currentCoroutineContext()[ContinuationInterceptor] as CoroutineDispatcher
            val engine = provider.open(DefaultUpgradedConnection(server, executor), negotiated, EngineLimits())
            val budget = RuntimeBudget(1024)
            try {
                val header = neton.io.bytes.Buffer()
                header.writeBytes(byteArrayOf(0x82.toByte(), 0xfe.toByte(), 0x10, 0, 0, 0, 0, 0))
                while (!header.isEmpty) peer.write(header)
                assertFailsWith<WebSocketCapacityException> { engine.receive(budget) }
                assertEquals(0L, budget.usage)
            } finally { engine.abort(); peer.close() }
        }
    }

    @Test fun fragmentedPayloadIsChargedOnceAtExactLimit() = runReactor {
        withTimeout(3000) {
            val provider = DefaultWebSocketEngineProvider()
            val negotiated = assertIs<HandshakeResult.Accepted>(provider.handshake(request())).negotiated
            val (server, peer) = memoryStreamPair()
            val executor = currentCoroutineContext()[ContinuationInterceptor] as CoroutineDispatcher
            val engine = provider.open(DefaultUpgradedConnection(server, executor), negotiated, EngineLimits())
            val budget = RuntimeBudget(2)
            try {
                val frames = neton.io.bytes.Buffer()
                frames.writeBytes(byteArrayOf(0x01, 0x81.toByte(), 0, 0, 0, 0, 97,
                    0x80.toByte(), 0x81.toByte(), 0, 0, 0, 0, 98))
                while (!frames.isEmpty) peer.write(frames)
                val message = assertIs<WebSocketEngineEvent.Text>(engine.receive(budget))
                assertEquals("ab", message.text)
                assertEquals(2L, budget.usage)
                assertEquals(2L, message.lease.bytes)
                message.lease.release()
                assertEquals(0L, budget.usage)
            } finally { engine.abort(); peer.close() }
        }
    }
}
