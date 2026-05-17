package neton.ai.adapter.openaicompatible.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class OpenAiStreamChunk(
    val id: String? = null,
    val model: String? = null,
    val choices: List<OpenAiStreamChoice> = emptyList(),
    val usage: OpenAiUsage? = null,
)

@Serializable
internal data class OpenAiStreamChoice(
    val index: Int = 0,
    val delta: OpenAiStreamDelta = OpenAiStreamDelta(),
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
internal data class OpenAiStreamDelta(
    val role: String? = null,
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<OpenAiStreamToolCallDelta>? = null,
)

@Serializable
internal data class OpenAiStreamToolCallDelta(
    val index: Int,
    val id: String? = null,
    val type: String? = null,
    val function: OpenAiStreamFunctionDelta? = null,
)

@Serializable
internal data class OpenAiStreamFunctionDelta(
    val name: String? = null,
    val arguments: String? = null,
)
