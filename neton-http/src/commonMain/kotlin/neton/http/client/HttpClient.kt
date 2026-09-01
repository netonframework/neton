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
 * Implementations are responsible for:
 *  - per-platform Ktor engine selection (Darwin / CIO / WinHttp)
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
            return factory(cfg)
        }

    }
}
