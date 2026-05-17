package neton.ai.adapter.anthropic.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
internal data class AnthropicMessagesRequest(
    val model: String,
    val messages: List<AnthropicMessage>,
    @SerialName("max_tokens") val maxTokens: Int,            // REQUIRED per Anthropic API
    val system: String? = null,                              // merged from AiRole.System messages
    val temperature: Double? = null,
    @SerialName("top_p") val topP: Double? = null,
    @SerialName("stop_sequences") val stopSequences: List<String>? = null,
    val tools: List<AnthropicToolDef>? = null,
    @SerialName("tool_choice") val toolChoice: AnthropicToolChoice? = null,
    val stream: Boolean? = null,
)

@Serializable
internal data class AnthropicMessage(
    val role: String,                                        // "user" | "assistant"
    val content: List<AnthropicContentBlock>,
)

@Serializable(with = AnthropicContentBlockSerializer::class)
internal sealed interface AnthropicContentBlock {
    val type: String
    @Serializable @SerialName("text") data class Text(val text: String) : AnthropicContentBlock {
        override val type: String get() = "text"
    }
    @Serializable @SerialName("tool_use") data class ToolUse(
        val id: String, val name: String, val input: JsonElement,
    ) : AnthropicContentBlock {
        override val type: String get() = "tool_use"
    }
    @Serializable @SerialName("tool_result") data class ToolResult(
        @SerialName("tool_use_id") val toolUseId: String,
        val content: String,
        @SerialName("is_error") val isError: Boolean? = null,
    ) : AnthropicContentBlock {
        override val type: String get() = "tool_result"
    }
}

/** Polymorphic serializer keyed on "type" field (Anthropic wire format). */
internal object AnthropicContentBlockSerializer : kotlinx.serialization.json.JsonContentPolymorphicSerializer<AnthropicContentBlock>(
    AnthropicContentBlock::class,
) {
    override fun selectDeserializer(element: kotlinx.serialization.json.JsonElement): kotlinx.serialization.DeserializationStrategy<AnthropicContentBlock> {
        val obj = element as kotlinx.serialization.json.JsonObject
        val typePrimitive = obj["type"] as? kotlinx.serialization.json.JsonPrimitive
        val t = typePrimitive?.content
        return when (t) {
            "text" -> AnthropicContentBlock.Text.serializer()
            "tool_use" -> AnthropicContentBlock.ToolUse.serializer()
            "tool_result" -> AnthropicContentBlock.ToolResult.serializer()
            else -> throw kotlinx.serialization.SerializationException("Unknown Anthropic content block type: $t")
        }
    }
}

@Serializable
internal data class AnthropicToolDef(
    val name: String,
    val description: String,
    @SerialName("input_schema") val inputSchema: JsonElement,
)

@Serializable
internal data class AnthropicToolChoice(
    val type: String,                                        // "auto" | "any" | "tool"
    val name: String? = null,                                // only for type="tool"
)
