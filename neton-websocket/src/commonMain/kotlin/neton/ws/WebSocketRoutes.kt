@file:OptIn(neton.ws.spi.ExperimentalWebSocketEngineApi::class, kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package neton.ws

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import neton.core.component.NetonContext
import neton.core.http.*
import neton.core.http.adapter.HttpAdapter
import neton.core.http.upgrade.*
import neton.core.interfaces.*
import neton.ws.spi.*
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt

fun RequestEngine.webSocket(path: String, subprotocols: List<String> = emptyList(), handler: suspend (WebSocketSession) -> Unit) {
    registerRoute(RouteDefinition(path, HttpMethod.GET, object : RouteHandler {
        override suspend fun invoke(context: HttpContext, args: HandlerArgs): Any? = error("Upgrade endpoint required")
    }, upgrade = webSocketEndpoint(subprotocols) { session, _, _ -> handler(session) }))
}

fun neton.routing.RouteGroupScope.webSocket(path: String, subprotocols: List<String> = emptyList(), handler: suspend (WebSocketSession) -> Unit) {
    upgrade(path, webSocketEndpoint(subprotocols) { session, _, _ -> handler(session) })
}

/** Shared by DSL and generated controller routes; authentication is performed by HTTP first. */
fun webSocketEndpoint(
    subprotocols: List<String> = emptyList(),
    handler: suspend (WebSocketSession, HttpContext, HandlerArgs) -> Unit,
): UpgradeEndpoint = preparedWebSocketEndpoint(subprotocols) { context, args ->
    { session -> handler(session, context, args) }
}

/** Parameter binding runs during HTTP, before accepting 101, not inside the upgraded session. */
fun preparedWebSocketEndpoint(
    subprotocols: List<String> = emptyList(),
    prepare: suspend (HttpContext, HandlerArgs) -> suspend (WebSocketSession) -> Unit,
): UpgradeEndpoint = object : UpgradeEndpoint {
    private val protocols = subprotocols.toList().also { values ->
        require(values.distinct().size == values.size && values.all { it.isNotEmpty() && it.all { c -> c.isLetterOrDigit() && c.code < 128 || c in "!#$%&'*+-.^_`|~" } })
    }
    override fun validate(context: NetonContext) {
        val runtime = context.getOrNull<WebSocketRuntime>() ?: error("WebSocket route requires websocket { }")
        check(runtime.provider.supports(context.get<HttpAdapter>())) { "Incompatible WebSocket/HTTP engines" }
    }
    override suspend fun decide(context: HttpContext, args: HandlerArgs): UpgradeDecision {
        val runtime = context.getApplicationContext()?.getOrNull<WebSocketRuntime>()
            ?: return UpgradeDecision.Reject(503)
        val request = context.request
        if (!runtime.acceptOrigin(request)) return UpgradeDecision.Reject(403)
        if (request.header("Transfer-Encoding") != null || (request.header("Content-Length")?.toLongOrNull() ?: 0) != 0L) return UpgradeDecision.Reject(400)
        val headers = request.headers.toMap()
        val offered = headers.entries.filter { it.key.equals("Sec-WebSocket-Protocol", true) }.flatMap { it.value }
            .flatMap { it.split(',') }.map { it.trim() }
        val selected = protocols.firstOrNull { it in offered }
        if (protocols.isNotEmpty() && selected == null) return UpgradeDecision.Reject(400)
        val result = runtime.provider.handshake(HandshakeRequest("GET", request.url, headers, request.version), HandshakeOffer(selected))
        if (result is HandshakeResult.Rejected) return UpgradeDecision.Reject(result.status, result.headers)
        result as HandshakeResult.Accepted
        val handler = prepare(context, args)
        return runtime.ticket(result, context, selected, handler)
            ?: UpgradeDecision.Reject(503)
    }
}

internal fun normalizeOrigin(value: String): String? {
    val match = Regex("^(https?)://(\\[[0-9A-Fa-f:]+]|[A-Za-z0-9.-]+)(?::([0-9]{1,5}))?$", RegexOption.IGNORE_CASE).matchEntire(value) ?: return null
    val scheme = match.groupValues[1].lowercase()
    val host = match.groupValues[2].lowercase()
    val port = match.groupValues[3].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: if (scheme == "https") 443 else 80
    if (port !in 1..65535) return null
    return "$scheme://$host:$port"
}

internal class WebSocketRuntime(val config: WebSocketConfig, val provider: WebSocketEngineProvider) {
    val buffered = RuntimeBudget(config.maxBufferedBytes)
    val pendingBytes = RuntimeBudget(config.maxSuspendedSendBytes)
    val pendingCount = RuntimeBudget(config.maxSuspendedSendsTotal.toLong())
    private val connections = RuntimeBudget(config.maxConnections.toLong())
    val shutdown = CompletableDeferred<Unit>()
    private val draining = AtomicBoolean(false)

    fun acceptOrigin(request: HttpRequest): Boolean {
        val origins = request.headers.toMap().entries.filter { it.key.equals("Origin", true) }.flatMap { it.value }
        if (origins.isEmpty()) return config.allowMissingOrigin
        if (origins.size != 1) return false
        val origin = normalizeOrigin(origins.single()) ?: return false
        if (config.allowedOrigins.isNotEmpty()) return config.allowedOrigins.any { normalizeOrigin(it) == origin }
        val hosts = request.headers.toMap().entries.filter { it.key.equals("Host", true) }.flatMap { it.value }
        if (hosts.size != 1) return false
        return normalizeOrigin("${if (request.isSecure) "https" else "http"}://${hosts.single()}") == origin
    }

    fun ticket(result: HandshakeResult.Accepted, context: HttpContext, selected: String?, handler: suspend (WebSocketSession) -> Unit): UpgradeDecision.Accept? {
        if (draining.load() || !connections.tryReserve(1)) return null
        if (draining.load()) { connections.release(1); return null }
        return object : UpgradeDecision.Accept {
            override val headers = result.headers
            private val released = AtomicBoolean(false)
            private val started = AtomicBoolean(false)
            override suspend fun run(connection: UpgradedConnection, shutdown: Deferred<Unit>) {
                check(started.compareAndSet(false, true)) { "Upgrade ticket consumed twice" }
                check(!released.load()) { "Released upgrade ticket" }
                val engine = provider.open(connection, result.negotiated, config.engineLimits)
                ManagedWebSocketSession(this@WebSocketRuntime, engine, context, selected).run(shutdown, handler)
            }
            override fun release() { if (released.compareAndSet(false, true)) connections.release(1) }
        }
    }

    suspend fun stop() {
        draining.store(true)
        shutdown.complete(Unit)
        withTimeoutOrNull(config.closeTimeoutMillis + config.handlerShutdownMillis) {
            while (connections.usage != 0L) {
                val generation = connections.changed.value
                if (connections.usage != 0L) connections.changed.first { it != generation }
            }
        }
    }
}
