package neton.http.client

import io.ktor.client.engine.HttpClientEngineFactory
import neton.core.annotations.InternalNetonApi

/**
 * The Ktor-backed client, chosen explicitly: `HttpClient.createWith(::createKtorHttpClient) { }`.
 *
 * The no-argument `HttpClient.create { }` is neton-http's built-in client; this module does not
 * declare a second one (spec zh-hans/spec/http-engine.md §4.2), so an application that wants Ktor
 * says so in source, and one that does not never gets it by accident.
 */
@OptIn(InternalNetonApi::class)
fun createKtorHttpClient(config: HttpClientConfig): HttpClient =
    KtorHttpClient(defaultTimeout = config.toEffectiveTimeout(), proxyUrl = config.proxyUrl)

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
