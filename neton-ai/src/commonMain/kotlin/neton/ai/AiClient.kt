package neton.ai

import neton.ai.builder.GenerateTextRequestBuilder

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
    suspend fun close()

    companion object {
        // Standalone Companion.create factory added in Task 17 once AiConfig DSL exists.
    }
}
