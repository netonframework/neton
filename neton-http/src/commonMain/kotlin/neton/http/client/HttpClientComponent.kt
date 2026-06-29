package neton.http.client

import neton.core.Neton
import neton.core.component.NetonComponent
import neton.core.component.NetonContext
import neton.logging.LoggerFactory

/**
 * Thin Neton Framework adapter — binds a [NetonHttpClient] (built via the standalone factory)
 * into [NetonContext] for downstream Neton modules.
 *
 * `Neton.run { httpClient { requestMillis = 30_000 } }`
 *
 * Removing this file does NOT affect standalone usage via `NetonHttpClient.create { ... }`.
 */
object HttpClientComponent : NetonComponent<HttpClientConfig> {

    override fun defaultConfig(): HttpClientConfig = HttpClientConfig()

    override suspend fun init(ctx: NetonContext, config: HttpClientConfig) {
        val log = ctx.getOrNull(LoggerFactory::class)?.get("neton.http.client")

        // Build via the same standalone factory used by Mode 1 callers — single source of truth.
        // NetonHttpClient.create throws NetonHttpException on invalid config.
        val client = NetonHttpClient.create {
            connectMillis = config.connectMillis
            requestMillis = config.requestMillis
            socketMillis = config.socketMillis
            debug = config.debug
        }
        ctx.bind(NetonHttpClient::class, client)

        if (config.debug) {
            log?.info("HTTP client initialized via Neton component", mapOf(
                "connectMillis" to (config.connectMillis ?: 5_000),
                "requestMillis" to (config.requestMillis ?: 60_000),
                "socketMillis" to (config.socketMillis ?: 60_000),
            ))
        }
    }

    override suspend fun stop(ctx: NetonContext) {
        ctx.getOrNull(NetonHttpClient::class)?.close()
    }
}

/** DSL entry: `httpClient { ... }` */
fun Neton.LaunchBuilder.httpClient(block: HttpClientConfig.() -> Unit = {}) {
    install(HttpClientComponent, block)
}
