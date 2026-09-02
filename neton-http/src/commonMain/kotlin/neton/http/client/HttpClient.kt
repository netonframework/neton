package neton.http.client

import kotlinx.coroutines.flow.Flow

/** Native-safe outbound client factory. External engines use constructor/function references. */
typealias HttpClientFactory = (HttpClientConfig) -> HttpClient

/**
 * Provider-neutral outbound HTTP client. Public API of neton-http.
 *
 * Built by the application, never by a framework component:
 * `val client = HttpClient.create { requestMillis = 30_000 }`.
 *
 * Ownership: the creator closes it. Register it with `ctx.lifecycle` so shutdown is
 * deterministic; modules handed a client only borrow it and must not close it.
 *
 * The no-argument `HttpClient.create { }` is not declared here. An engine module
 * declares it in this package (spec zh-hans/spec/http-engine.md §4): depend on
 * `neton-http-hyper4k` and it resolves to the hyper4k client, depend on
 * `neton-http-ktor` and it resolves to Ktor. This file never names an engine.
 *
 * Implementations are responsible for:
 *  - timeout enforcement
 *  - typed error mapping (HttpClientException for failures)
 *  - cancellation propagation (Flow cancel → HTTP body close)
 *  - redaction of sensitive headers in any internal logging
 *
 * Downstream consumers (neton-ai, future neton-webhooks, etc.) consume this interface,
 * NEVER `io.ktor.client.*` directly.
 */
interface HttpClient {
    /**
     * Execute a one-shot HTTP request. Body fully buffered in [HttpClientResponse.body].
     * Throws [HttpClientException] on transport / HTTP failures.
     */
    suspend fun request(request: HttpClientRequest): HttpClientResponse

    /**
     * Open a streaming HTTP body. Flow emits [HttpClientStreamChunk.Bytes] (or [HttpClientStreamChunk.Text] for text bodies)
     * followed by exactly one [HttpClientStreamChunk.End].
     *
     * Cancellation: cancelling the Flow collection closes the underlying HTTP response body,
     * which closes the TCP connection. Server observes the close and stops generating.
     *
     * Throws [HttpClientException] on connection failures before the first chunk is read.
     * Errors mid-stream propagate as Flow exceptions (also [HttpClientException]).
     */
    fun stream(request: HttpClientRequest): Flow<HttpClientStreamChunk>

    /** Release engine resources. Idempotent. */
    suspend fun close()

    /**
     * What this client can actually do (spec http-engine.md §5.2).
     *
     * No default on purpose: an empty default lets a new implementation quietly
     * support nothing, a full default lets it quietly claim everything. Either
     * way the mismatch surfaces at runtime instead of at the declaration.
     */
    val capabilities: Set<HttpClientCapability>

    companion object {
        /**
         * Standalone factory with an application-selected transport implementation.
         *
         * The same config validation runs before an external factory is invoked.
         */
        fun createWith(
            factory: HttpClientFactory,
            block: HttpClientConfig.() -> Unit = {},
        ): HttpClient {
            val cfg = HttpClientConfig().apply(block)
            val errors = cfg.validate()
            if (errors.isNotEmpty()) {
                throw HttpClientException(HttpClientError.Unknown(
                    "Invalid HTTP client config: ${errors.joinToString()}", null,
                ))
            }
            val client = factory(cfg)
            // A proxy the engine cannot honour must fail here, not be ignored: an
            // ignored proxyUrl means requests leave through the wrong network path,
            // which is a security problem dressed up as a convenience one.
            if (cfg.proxyUrl != null && HttpClientCapability.PROXY !in client.capabilities) {
                throw HttpClientException(HttpClientError.Unknown(
                    "proxyUrl is set but this HTTP client does not declare " +
                        "${HttpClientCapability.PROXY} (capabilities: ${client.capabilities}). " +
                        "Use an engine with proxy support or drop proxyUrl.",
                    null,
                ))
            }
            return client
        }

    }
}
