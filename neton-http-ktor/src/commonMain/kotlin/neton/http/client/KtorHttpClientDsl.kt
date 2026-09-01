package neton.http.client

import io.ktor.client.engine.HttpClientEngineFactory
import neton.core.Neton
import neton.core.annotations.InternalNetonApi

/**
 * The Ktor-backed client entry points.
 *
 * They live here rather than on [HttpClient] itself so neton-http carries no
 * Ktor dependency. Declared in package `neton.http.client`, so applications keep
 * writing `HttpClient.create { }` and only add this module to their build.
 */
fun HttpClient.Companion.create(block: HttpClientConfig.() -> Unit = {}): HttpClient =
    createWith(::createKtorClient, block)

/**
 * Construct with a caller-supplied Ktor engine. Intended for tests (MockEngine) and
 * for production cases that need engine-specific configuration.
 */
@OptIn(InternalNetonApi::class)
fun HttpClient.Companion.createWithEngine(
    engineFactory: HttpClientEngineFactory<*>,
    block: HttpClientConfig.() -> Unit = {},
): HttpClient = createWith({ cfg ->
    KtorHttpClient(
        engineFactory = engineFactory,
        defaultTimeout = cfg.toEffectiveTimeout(),
        proxyUrl = cfg.proxyUrl,
    )
}, block)

@OptIn(InternalNetonApi::class)
private fun createKtorClient(config: HttpClientConfig): HttpClient =
    KtorHttpClient(defaultTimeout = config.toEffectiveTimeout(), proxyUrl = config.proxyUrl)
