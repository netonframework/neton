@file:OptIn(neton.websocket.spi.ExperimentalWebSocketEngineApi::class)

package neton.websocket

import neton.websocket.config.WebSocketConfig

import neton.core.Neton
import neton.core.component.NetonComponent
import neton.core.component.NetonContext
import neton.core.http.adapter.HttpAdapter
import neton.websocket.spi.EngineLimits
import neton.websocket.spi.WebSocketEngineCapability
import neton.websocket.spi.WebSocketEngineProvider


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
        val logger = ctx.getOrNull<neton.logging.LoggerFactory>()?.get("neton.websocket")
        ctx.bind(WebSocketRuntime::class, WebSocketRuntime(effective, ctx.get<WebSocketEngineProvider>()) {
            logger?.warn(it)
        })
    }

    override suspend fun prepare(ctx: NetonContext) {
        val adapter = ctx.getOrNull<HttpAdapter>() ?: error("WebSocket requires an installed HTTP adapter")
        val provider = ctx.get<WebSocketEngineProvider>()
        check(provider.supports(adapter)) { "WebSocket provider ${provider.name} is incompatible with ${adapter.adapterName()}" }
        val missing = (ctx.get<WebSocketConfig>().requiredCapabilities + setOf(WebSocketEngineCapability.MESSAGE_LIMITS,
            WebSocketEngineCapability.PREALLOCATION_BUDGET)) - provider.capabilities
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
