@file:OptIn(neton.ws.spi.ExperimentalWebSocketEngineApi::class)

package neton.ws

import neton.core.Neton
import neton.core.component.NetonComponent
import neton.core.component.NetonContext
import neton.core.http.adapter.HttpAdapter
import neton.ws.spi.EngineLimits
import neton.ws.spi.WebSocketEngineCapability
import neton.ws.spi.WebSocketEngineProvider

class WebSocketConfig {
    var engineLimits: EngineLimits = EngineLimits()
    /** Must be explicit: an unsupported contract cannot silently degrade. */
    var requiredCapabilities: Set<WebSocketEngineCapability> = WebSocketEngineCapability.entries.toSet()
    var maxConnections: Int = 16384
    var maxBufferedBytes: Long = 512L * 1024 * 1024
    var maxQueuedBytesPerConnection: Long = 8L * 1024 * 1024
    var queueCapacity: Int = 16
    var maxSuspendedSends: Int = 16
    var maxSuspendedSendsTotal: Int = 4096
    var maxSuspendedSendBytes: Long = 64L * 1024 * 1024
    var pingIntervalMillis: Long = 30000
    var pongTimeoutMillis: Long = 10000
    var idleTimeoutMillis: Long = 0
    var consumerTimeoutMillis: Long = 30000
    var writeTimeoutMillis: Long = 10000
    var closeTimeoutMillis: Long = 5000
    var handlerShutdownMillis: Long = 1000
    /** Empty means same-origin. Absent Origin is allowed for non-browser clients. */
    var allowedOrigins: Set<String> = emptySet()
    var allowMissingOrigin: Boolean = true

    internal fun snapshot() = WebSocketConfig().also {
        it.engineLimits = engineLimits; it.requiredCapabilities = requiredCapabilities.toSet()
        it.maxConnections = maxConnections; it.maxBufferedBytes = maxBufferedBytes
        it.maxQueuedBytesPerConnection = maxQueuedBytesPerConnection; it.queueCapacity = queueCapacity
        it.maxSuspendedSends = maxSuspendedSends; it.maxSuspendedSendsTotal = maxSuspendedSendsTotal
        it.maxSuspendedSendBytes = maxSuspendedSendBytes
        it.pingIntervalMillis = pingIntervalMillis; it.pongTimeoutMillis = pongTimeoutMillis
        it.idleTimeoutMillis = idleTimeoutMillis; it.consumerTimeoutMillis = consumerTimeoutMillis
        it.writeTimeoutMillis = writeTimeoutMillis; it.closeTimeoutMillis = closeTimeoutMillis
        it.handlerShutdownMillis = handlerShutdownMillis
        it.allowedOrigins = allowedOrigins.toSet(); it.allowMissingOrigin = allowMissingOrigin
    }

    internal fun validate() {
        require(maxConnections > 0 && queueCapacity > 0)
        require(maxSuspendedSends > 0 && maxSuspendedSendsTotal > 0 && maxSuspendedSendBytes > 0)
        require(maxQueuedBytesPerConnection >= engineLimits.maxMessageBytes.toLong()) {
            "maxQueuedBytesPerConnection must fit maxMessageBytes"
        }
        require(maxBufferedBytes >= maxQueuedBytesPerConnection)
        require(maxSuspendedSendBytes >= engineLimits.maxMessageBytes.toLong() * 2)
        require(pingIntervalMillis >= 0 && pongTimeoutMillis >= 0 && idleTimeoutMillis >= 0)
        require(consumerTimeoutMillis > 0 && writeTimeoutMillis > 0 && closeTimeoutMillis > 0 && handlerShutdownMillis > 0)
        require(listOf(pingIntervalMillis, pongTimeoutMillis, idleTimeoutMillis, consumerTimeoutMillis,
            writeTimeoutMillis, closeTimeoutMillis, handlerShutdownMillis).all { it <= Int.MAX_VALUE.toLong() }) {
            "WebSocket timeouts must not exceed Int.MAX_VALUE milliseconds"
        }
        require(allowedOrigins.none { it == "*" || normalizeOrigin(it) == null }) { "Use explicit http/https origins" }
    }
}

/** Installs one application-scoped runtime and a replaceable protocol provider. */
class WebSocketComponent(
    private val providerFactory: () -> WebSocketEngineProvider = ::createDefaultWebSocketEngineProvider,
) : NetonComponent<WebSocketConfig> {
    override fun defaultConfig() = WebSocketConfig()

    override suspend fun init(ctx: NetonContext, config: WebSocketConfig) {
        val effective = config.snapshot().apply { loadApplicationConfig(ctx) }
        effective.validate()
        check(ctx.getOrNull<WebSocketEngineProvider>() == null) { "WebSocket is already installed" }
        ctx.bind(WebSocketEngineProvider::class, providerFactory())
        ctx.bind(WebSocketConfig::class, effective.snapshot())
        ctx.bind(WebSocketRuntime::class, WebSocketRuntime(effective, ctx.get<WebSocketEngineProvider>()))
    }

    override suspend fun prepare(ctx: NetonContext) {
        val adapter = ctx.getOrNull<HttpAdapter>() ?: error("WebSocket requires an installed HTTP adapter")
        val provider = ctx.get<WebSocketEngineProvider>()
        check(provider.supports(adapter)) { "WebSocket provider ${provider.name} is incompatible with ${adapter.adapterName()}" }
        val missing = (ctx.get<WebSocketConfig>().requiredCapabilities + WebSocketEngineCapability.entries) - provider.capabilities
        check(missing.isEmpty()) { "WebSocket provider ${provider.name} lacks required capabilities: $missing" }
    }

    override suspend fun stop(ctx: NetonContext) { ctx.get<WebSocketRuntime>().stop() }
}

internal expect fun createDefaultWebSocketEngineProvider(): WebSocketEngineProvider

/** Explicit factory wins; additional dependencies never replace the built-in provider. */
fun Neton.LaunchBuilder.websocket(
    providerFactory: () -> WebSocketEngineProvider = ::createDefaultWebSocketEngineProvider,
    block: WebSocketConfig.() -> Unit = {},
) {
    install(WebSocketComponent(providerFactory), block)
}
