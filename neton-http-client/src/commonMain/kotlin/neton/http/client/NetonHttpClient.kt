package neton.http.client

import kotlinx.coroutines.flow.Flow
import neton.http.client.internal.DefaultNetonHttpClient

/**
 * Provider-neutral HTTP client. Public API of neton-http-client.
 *
 * **Dual usage**:
 *   1. Standalone (any KMP project): `val client = NetonHttpClient.create { requestMillis = 30_000 }`
 *   2. Neton Framework component (PR1+ neton-ai etc.): `Neton.run { httpClient { ... } }`; downstream
 *      modules use `ctx.get(NetonHttpClient::class)`.
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
         * Standalone factory. Constructs a [NetonHttpClient] from a config DSL without
         * requiring any Neton runtime (`Neton.run`, `NetonContext`, etc.).
         *
         * @throws NetonHttpException(NetonHttpError.Unknown) on invalid config (non-positive timeouts).
         */
        fun create(block: HttpClientConfig.() -> Unit = {}): NetonHttpClient {
            val cfg = HttpClientConfig().apply(block)
            val errors = cfg.validate()
            if (errors.isNotEmpty()) {
                throw NetonHttpException(NetonHttpError.Unknown(
                    "Invalid HTTP client config: ${errors.joinToString()}", null,
                ))
            }
            return DefaultNetonHttpClient(defaultTimeout = cfg.toEffectiveTimeout())
        }

        /**
         * Construct with a caller-supplied Ktor engine factory. Intended for tests (MockEngine)
         * and advanced production cases (custom engine config). Use [create] for normal usage.
         */
        fun createWithEngine(
            engineFactory: io.ktor.client.engine.HttpClientEngineFactory<*>,
            block: HttpClientConfig.() -> Unit = {},
        ): NetonHttpClient {
            val cfg = HttpClientConfig().apply(block)
            val errors = cfg.validate()
            if (errors.isNotEmpty()) {
                throw NetonHttpException(NetonHttpError.Unknown(
                    "Invalid HTTP client config: ${errors.joinToString()}", null,
                ))
            }
            return DefaultNetonHttpClient(engineFactory = engineFactory, defaultTimeout = cfg.toEffectiveTimeout())
        }
    }
}
