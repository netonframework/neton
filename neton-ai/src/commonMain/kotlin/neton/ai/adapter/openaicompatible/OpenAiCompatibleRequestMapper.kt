// neton-ai/src/commonMain/kotlin/neton/ai/adapter/openaicompatible/OpenAiCompatibleRequestMapper.kt
package neton.ai.adapter.openaicompatible

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import neton.ai.AiContent
import neton.ai.AiMessage
import neton.ai.AiRole
import neton.ai.ToolChoice
import neton.ai.adapter.openaicompatible.dto.OpenAiChatRequest
import neton.ai.adapter.openaicompatible.dto.OpenAiFunctionCall
import neton.ai.adapter.openaicompatible.dto.OpenAiFunctionDef
import neton.ai.adapter.openaicompatible.dto.OpenAiMessage
import neton.ai.adapter.openaicompatible.dto.OpenAiTool
import neton.ai.adapter.openaicompatible.dto.OpenAiToolCall
import neton.ai.provider.ProviderCallRequest

internal class OpenAiCompatibleRequestMapper(
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false },
) {
    fun toWire(modelName: String, req: ProviderCallRequest): OpenAiChatRequest = OpenAiChatRequest(
        model = modelName,
        messages = req.messages.map(::messageToWire),
        temperature = req.temperature,
        maxTokens = req.maxTokens,
        topP = req.topP,
        stop = req.stopSequences.takeIf { it.isNotEmpty() },
        tools = req.tools.takeIf { it.isNotEmpty() }?.map { def ->
            OpenAiTool(function = OpenAiFunctionDef(
                name = def.name,
                description = def.description,
                parameters = json.parseToJsonElement(def.inputSchemaJson),
            ))
        },
        toolChoice = toolChoiceToWire(req.toolChoice),
        stream = false,
    )

    private fun messageToWire(m: AiMessage): OpenAiMessage = OpenAiMessage(
        role = when (m.role) {
            AiRole.System -> "system"
            AiRole.User -> "user"
            AiRole.Assistant -> "assistant"
            AiRole.Tool -> "tool"
        },
        content = m.content
            .filterIsInstance<AiContent.Text>()
            .joinToString("\n") { it.text }
            .takeIf { it.isNotEmpty() || m.toolCalls.isEmpty() },  // null when assistant has only tool_calls
        toolCallId = m.toolCallId,
        toolCalls = m.toolCalls.takeIf { it.isNotEmpty() }?.map { tc ->
            OpenAiToolCall(
                id = tc.id,
                function = OpenAiFunctionCall(name = tc.name, arguments = tc.argumentsJson),
            )
        },
    )

    private fun toolChoiceToWire(c: ToolChoice): kotlinx.serialization.json.JsonElement = when (c) {
        ToolChoice.Auto -> JsonPrimitive("auto")
        ToolChoice.None -> JsonPrimitive("none")
        ToolChoice.Required -> JsonPrimitive("required")
        is ToolChoice.Named -> buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject { put("name", c.name) })
        }
    }
}
