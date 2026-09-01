package neton.http.client

import io.ktor.client.engine.HttpClientEngineFactory
import neton.core.Neton
import neton.core.annotations.InternalNetonApi

/**
 * The Ktor-backed client entry points.
 *
 * They live here rather than on [NetonHttpClient] itself so neton-http carries no
 * Ktor dependency. Declared in package `neton.http.client`, so applications keep
 * writing `NetonHttpClient.create { }` and only add this module to their build.
 */
fun NetonHttpClient.Companion.create(block: HttpClientConfig.() -> Unit = {}): NetonHttpClient =
    createWith(::createKtorClient, block)

/**
 * Construct with a caller-supplied Ktor engine. Intended for tests (MockEngine) and
 * for production cases that need engine-specific configuration.
 */
@OptIn(InternalNetonApi::class)
fun NetonHttpClient.Companion.createWithEngine(
    engineFactory: HttpClientEngineFactory<*>,
    block: HttpClientConfig.() -> Unit = {},
): NetonHttpClient = createWith({ cfg ->
    DefaultNetonHttpClient(
        engineFactory = engineFactory,
        defaultTimeout = cfg.toEffectiveTimeout(),
        proxyUrl = cfg.proxyUrl,
    )
}, block)

@OptIn(InternalNetonApi::class)
private fun createKtorClient(config: HttpClientConfig): NetonHttpClient =
    DefaultNetonHttpClient(defaultTimeout = config.toEffectiveTimeout(), proxyUrl = config.proxyUrl)

/**
 * DSL entry: `httpClient { ... }`, backed by Ktor.
 *
 * Same reason as [create]: neton-http keeps the explicit
 * `httpClient(factory) { }` overload and never names an engine.
 */
fun Neton.LaunchBuilder.httpClient(block: HttpClientConfig.() -> Unit = {}) {
    httpClient(
        factory = { config ->
            NetonHttpClient.create {
                connectMillis = config.connectMillis
                requestMillis = config.requestMillis
                socketMillis = config.socketMillis
                debug = config.debug
                proxyUrl = config.proxyUrl
            }
        },
        block = block,
    )
}
