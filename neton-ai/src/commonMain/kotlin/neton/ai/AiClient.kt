package neton.ai

import kotlinx.coroutines.flow.Flow
import neton.ai.builder.EmbeddingRequestBuilder
import neton.ai.builder.GenerateTextRequestBuilder
import neton.ai.builder.StreamTextRequestBuilder

/**
 * Lightweight log sink — a function that accepts a sanitized log line. Decoupled from
 * neton-logging so standalone usage (Mode 1) doesn't pull in the logging runtime.
 *
 * In Mode 2 (AiComponent), AiComponent wires this to neton-logging's Logger. In Mode 1,
 * callers can pass `::println` or any other sink, or omit entirely (no logs).
 */
typealias AiLogSink = (line: String) -> Unit

/**
 * Provider-neutral AI client. v0.1 ships only generateText (non-streaming).
 *
 * **Dual usage**:
 *   1. Standalone (Task 17 adds Companion.create): `val ai = AiClient.create { ... }`
 *   2. Neton component (Task 18): `Neton.run { ai { ... } }`; downstream via `ctx.get(AiClient::class)`.
 */
interface AiClient {
    suspend fun generateText(block: GenerateTextRequestBuilder.() -> Unit): GenerateTextResult
    suspend fun generateText(request: GenerateTextRequest): GenerateTextResult
    fun streamText(block: StreamTextRequestBuilder.() -> Unit): Flow<AiStreamEvent>
    fun streamText(request: StreamTextRequest): Flow<AiStreamEvent>
    suspend fun embed(block: EmbeddingRequestBuilder.() -> Unit): EmbeddingResult
    suspend fun embed(request: EmbeddingRequest): EmbeddingResult
    suspend fun close()

    companion object {
        /**
         * Standalone factory (Mode 1). Constructs an AiClient from a DSL block WITHOUT requiring
         * any Neton runtime (Neton.run / NetonContext). Caller must provide an NetonHttpClient.
         *
         * Example:
         *   val ai = AiClient.create {
         *       httpClient = NetonHttpClient.create { requestMillis = 30_000 }
         *       providers {
         *           openAiCompatible("openai") { baseUrl = "..."; apiKey = "sk-..." }
         *       }
         *       routing { defaultModel = "openai:gpt-4o-mini" }
         *   }
         *
         * @throws AiException(InvalidRequest) on invalid config.
         */
        fun create(block: AiConfig.() -> Unit): AiClient {
            val cfg = AiConfig().apply(block)
            return neton.ai.internal.AiClientFactory.createFromConfig(cfg)
        }
    }
}
