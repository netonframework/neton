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
    var requiredCapabilities: Set<WebSocketEngineCapability> = setOf(WebSocketEngineCapability.MESSAGE_LIMITS)
}

/** Provider installation only; route integration and the managed session runtime are separate work. */
@neton.ws.spi.ExperimentalWebSocketEngineApi
class WebSocketComponent(
    private val providerFactory: () -> WebSocketEngineProvider = ::createDefaultWebSocketEngineProvider,
) : NetonComponent<WebSocketConfig> {
    override fun defaultConfig() = WebSocketConfig()

    override suspend fun init(ctx: NetonContext, config: WebSocketConfig) {
        check(ctx.getOrNull<WebSocketEngineProvider>() == null) { "WebSocket is already installed" }
        ctx.bind(WebSocketEngineProvider::class, providerFactory())
        ctx.bind(WebSocketConfig::class, WebSocketConfig().also {
            it.engineLimits = config.engineLimits
            it.requiredCapabilities = config.requiredCapabilities.toSet()
        })
    }

    override suspend fun prepare(ctx: NetonContext) {
        val adapter = ctx.getOrNull<HttpAdapter>() ?: error("WebSocket requires an installed HTTP adapter")
        val provider = ctx.get<WebSocketEngineProvider>()
        check(provider.supports(adapter)) { "WebSocket provider ${provider.name} is incompatible with ${adapter.adapterName()}" }
        val missing = ctx.get<WebSocketConfig>().requiredCapabilities - provider.capabilities
        check(missing.isEmpty()) { "WebSocket provider ${provider.name} lacks required capabilities: $missing" }
    }
}

internal expect fun createDefaultWebSocketEngineProvider(): WebSocketEngineProvider

/** Explicit factory wins; additional dependencies never replace the built-in provider. */
@neton.ws.spi.ExperimentalWebSocketEngineApi
fun Neton.LaunchBuilder.websocket(
    providerFactory: () -> WebSocketEngineProvider = ::createDefaultWebSocketEngineProvider,
    block: WebSocketConfig.() -> Unit = {},
) {
    install(WebSocketComponent(providerFactory), block)
}
