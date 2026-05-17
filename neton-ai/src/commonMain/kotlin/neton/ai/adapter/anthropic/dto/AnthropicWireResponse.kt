// neton-ai/src/commonMain/kotlin/neton/ai/adapter/anthropic/dto/AnthropicWireResponse.kt
package neton.ai.adapter.anthropic.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class AnthropicMessagesResponse(
    val id: String? = null,
    val model: String? = null,
    val role: String = "assistant",
    val content: List<AnthropicContentBlock>,
    @SerialName("stop_reason") val stopReason: String? = null,
    @SerialName("stop_sequence") val stopSequence: String? = null,
    val usage: AnthropicUsage? = null,
)

@Serializable
internal data class AnthropicUsage(
    @SerialName("input_tokens") val inputTokens: Int? = null,
    @SerialName("output_tokens") val outputTokens: Int? = null,
)

/** Error envelope per Anthropic API. */
@Serializable
internal data class AnthropicErrorEnvelope(
    val type: String = "error",
    val error: AnthropicErrorBody,
)

@Serializable
internal data class AnthropicErrorBody(
    val type: String,        // "invalid_request_error" | "authentication_error" | "rate_limit_error" | "overloaded_error" | ...
    val message: String,
)
