// neton-ai/src/commonMain/kotlin/neton/ai/adapter/openaicompatible/dto/OpenAiWireRequest.kt
package neton.ai.adapter.openaicompatible.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
internal data class OpenAiChatRequest(
    val model: String,
    val messages: List<OpenAiMessage>,
    val temperature: Double? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    @SerialName("top_p") val topP: Double? = null,
    val stop: List<String>? = null,
    val tools: List<OpenAiTool>? = null,
    @SerialName("tool_choice") val toolChoice: JsonElement? = null,  // string OR object — keep flexible
    val stream: Boolean? = null,  // false for non-stream; PR2 sets true
)

@Serializable
internal data class OpenAiMessage(
    val role: String,                            // "system" | "user" | "assistant" | "tool"
    val content: String? = null,                 // null when assistant has only tool_calls
    val name: String? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    @SerialName("tool_calls") val toolCalls: List<OpenAiToolCall>? = null,
)

@Serializable
internal data class OpenAiTool(
    val type: String = "function",
    val function: OpenAiFunctionDef,
)

@Serializable
internal data class OpenAiFunctionDef(
    val name: String,
    val description: String,
    val parameters: JsonElement,                 // raw JSON Schema from AiToolDefinition.inputSchemaJson
)

@Serializable
internal data class OpenAiToolCall(
    val id: String,
    val type: String = "function",
    val function: OpenAiFunctionCall,
)

@Serializable
internal data class OpenAiFunctionCall(
    val name: String,
    val arguments: String,                       // JSON string per OpenAI spec
)
