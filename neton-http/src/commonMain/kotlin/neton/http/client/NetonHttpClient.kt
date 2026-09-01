package neton.http.client

import kotlinx.coroutines.flow.Flow

/** Native-safe outbound client factory. External engines use constructor/function references. */
typealias NetonHttpClientFactory = (HttpClientConfig) -> NetonHttpClient

/**
 * Provider-neutral outbound HTTP client. Public API of neton-http.
 *
 * Built by the application, never by a framework component:
 * `val client = NetonHttpClient.create { requestMillis = 30_000 }`.
 *
 * Ownership: the creator closes it. Register it with `ctx.lifecycle` so shutdown is
 * deterministic; modules handed a client only borrow it and must not close it.
 *
 * Implementations are responsible for:
 *  - per-platform Ktor engine selection (Darwin / CIO / WinHttp)
 *  - timeout enforcement
 *  - typed error mapping (NetonHttpException for failures)
 *  - cancellation propagation (Flow cancel → HTTP body close)
 *  - redaction of sensitive headers in any internal logging
 *
 * Downstream consumers (neton-ai, future neton-webhooks, etc.) consume this interface,
 * NEVER `io.ktor.client.*` directly.
 */
interface NetonHttpClient {
    /**
     * Execute a one-shot HTTP request. Body fully buffered in [NetonHttpResponse.body].
     * Throws [NetonHttpException] on transport / HTTP failures.
     */
    suspend fun request(request: NetonHttpRequest): NetonHttpResponse

    /**
     * Open a streaming HTTP body. Flow emits [NetonHttpStreamChunk.Bytes] (or [NetonHttpStreamChunk.Text] for text bodies)
     * followed by exactly one [NetonHttpStreamChunk.End].
     *
     * Cancellation: cancelling the Flow collection closes the underlying HTTP response body,
     * which closes the TCP connection. Server observes the close and stops generating.
     *
     * Throws [NetonHttpException] on connection failures before the first chunk is read.
     * Errors mid-stream propagate as Flow exceptions (also [NetonHttpException]).
     */
    fun stream(request: NetonHttpRequest): Flow<NetonHttpStreamChunk>

    /** Release engine resources. Idempotent. */
    suspend fun close()

    companion object {
        /**
         * Standalone factory with an application-selected transport implementation.
         *
         * The same config validation runs before an external factory is invoked.
         */
        fun createWith(
            factory: NetonHttpClientFactory,
            block: HttpClientConfig.() -> Unit = {},
        ): NetonHttpClient {
            val cfg = HttpClientConfig().apply(block)
            val errors = cfg.validate()
            if (errors.isNotEmpty()) {
                throw NetonHttpException(NetonHttpError.Unknown(
                    "Invalid HTTP client config: ${errors.joinToString()}", null,
                ))
            }
            return factory(cfg)
        }

    }
}
