package neton.ai.adapter.anthropic

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import neton.ai.AiContent
import neton.ai.AiMessage
import neton.ai.AiRole
import neton.ai.ToolChoice
import neton.ai.adapter.anthropic.dto.AnthropicContentBlock
import neton.ai.adapter.anthropic.dto.AnthropicMessage
import neton.ai.adapter.anthropic.dto.AnthropicMessagesRequest
import neton.ai.adapter.anthropic.dto.AnthropicToolChoice
import neton.ai.adapter.anthropic.dto.AnthropicToolDef
import neton.ai.provider.ProviderCallRequest

internal class AnthropicRequestMapper(
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false },
) {
    /** Anthropic requires max_tokens; default to this if caller omits. */
    private val defaultMaxTokens = 4096

    fun toWire(modelName: String, req: ProviderCallRequest): AnthropicMessagesRequest {
        // Pull out system messages (Anthropic has top-level `system: String`, NOT a role in messages)
        val systemParts = req.messages
            .filter { it.role == AiRole.System }
            .flatMap { it.content }
            .filterIsInstance<AiContent.Text>()
            .map { it.text }
        val system = if (systemParts.isEmpty()) null else systemParts.joinToString("\n\n")

        // Remaining messages: user/assistant/tool → assistant or user (tool becomes user-with-tool_result)
        val nonSystem = req.messages.filter { it.role != AiRole.System }
        val anthropicMessages = nonSystem.map(::messageToWire)

        val toolsActive = req.toolChoice !is ToolChoice.None
        val tools = if (toolsActive && req.tools.isNotEmpty()) req.tools.map { def ->
            AnthropicToolDef(
                name = def.name,
                description = def.description,
                inputSchema = json.parseToJsonElement(def.inputSchemaJson),
            )
        } else null

        val toolChoice = if (toolsActive && tools != null) toolChoiceToWire(req.toolChoice) else null

        return AnthropicMessagesRequest(
            model = modelName,
            messages = anthropicMessages,
            maxTokens = req.maxTokens ?: defaultMaxTokens,
            system = system,
            temperature = req.temperature,
            topP = req.topP,
            stopSequences = req.stopSequences.takeIf { it.isNotEmpty() },
            tools = tools,
            toolChoice = toolChoice,
            stream = false,
        )
    }

    private fun messageToWire(m: AiMessage): AnthropicMessage = when (m.role) {
        AiRole.User -> AnthropicMessage(role = "user", content = textBlocks(m))
        AiRole.Assistant -> AnthropicMessage(
            role = "assistant",
            content = textBlocks(m) + toolUseBlocks(m),
        )
        AiRole.Tool -> AnthropicMessage(
            role = "user",
            content = listOf(AnthropicContentBlock.ToolResult(
                toolUseId = m.toolCallId ?: error("Tool message must have toolCallId"),
                content = m.content.filterIsInstance<AiContent.Text>().joinToString("\n") { it.text },
                isError = null,
            )),
        )
        AiRole.System -> error("System messages must be merged into top-level 'system' field, not mapped per-message")
    }

    private fun textBlocks(m: AiMessage): List<AnthropicContentBlock.Text> =
        m.content.filterIsInstance<AiContent.Text>().map { AnthropicContentBlock.Text(it.text) }

    private fun toolUseBlocks(m: AiMessage): List<AnthropicContentBlock.ToolUse> =
        m.toolCalls.map { tc ->
            AnthropicContentBlock.ToolUse(
                id = tc.id,
                name = tc.name,
                input = json.parseToJsonElement(tc.argumentsJson),
            )
        }

    private fun toolChoiceToWire(c: ToolChoice): AnthropicToolChoice? = when (c) {
        ToolChoice.Auto -> AnthropicToolChoice(type = "auto")
        ToolChoice.None -> null  // never reached — handled above
        ToolChoice.Required -> AnthropicToolChoice(type = "any")
        is ToolChoice.Named -> AnthropicToolChoice(type = "tool", name = c.name)
    }
}
