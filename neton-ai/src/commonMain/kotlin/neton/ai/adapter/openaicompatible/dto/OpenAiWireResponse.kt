// neton-ai/src/commonMain/kotlin/neton/ai/adapter/openaicompatible/dto/OpenAiWireResponse.kt
package neton.ai.adapter.openaicompatible.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class OpenAiChatResponse(
    val id: String? = null,
    val model: String? = null,
    val choices: List<OpenAiChoice>,
    val usage: OpenAiUsage? = null,
)

@Serializable
internal data class OpenAiChoice(
    val index: Int = 0,
    val message: OpenAiResponseMessage,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
internal data class OpenAiResponseMessage(
    val role: String,
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<OpenAiResponseToolCall>? = null,
)

@Serializable
internal data class OpenAiResponseToolCall(
    val id: String,
    val type: String = "function",
    val function: OpenAiResponseFunctionCall,
)

@Serializable
internal data class OpenAiResponseFunctionCall(
    val name: String,
    val arguments: String,
)

@Serializable
internal data class OpenAiUsage(
    @SerialName("prompt_tokens") val promptTokens: Int? = null,
    @SerialName("completion_tokens") val completionTokens: Int? = null,
    @SerialName("total_tokens") val totalTokens: Int? = null,
)

/** Error envelope per OpenAI spec (also returned by DeepSeek / Qwen compat). */
@Serializable
internal data class OpenAiErrorEnvelope(val error: OpenAiErrorBody)

@Serializable
internal data class OpenAiErrorBody(
    val message: String,
    val type: String? = null,
    val code: String? = null,
)
