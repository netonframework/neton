package neton.ai.builder

import neton.ai.AiContent
import neton.ai.AiMessage
import neton.ai.AiModelId
import neton.ai.AiRole
import neton.ai.AiToolDefinition
import neton.ai.GenerateTextRequest
import neton.ai.ToolChoice

class GenerateTextRequestBuilder {
    var model: String? = null
    var modelPolicy: String? = null
    var temperature: Double? = null
    var maxTokens: Int? = null
    var topP: Double? = null
    var maxToolRounds: Int = 3
    var toolChoice: ToolChoice = ToolChoice.Auto
    var stopSequences: List<String> = emptyList()
    var metadata: Map<String, String> = emptyMap()

    private val messages = mutableListOf<AiMessage>()
    private val tools = mutableListOf<AiToolDefinition>()

    fun system(text: String) { messages += AiMessage(AiRole.System, listOf(AiContent.Text(text))) }
    fun user(text: String) { messages += AiMessage(AiRole.User, listOf(AiContent.Text(text))) }
    fun assistant(text: String) { messages += AiMessage(AiRole.Assistant, listOf(AiContent.Text(text))) }
    fun toolResult(callId: String, content: String) {
        messages += AiMessage(AiRole.Tool, listOf(AiContent.Text(content)), toolCallId = callId)
    }
    fun message(m: AiMessage) { messages += m }
    fun messages(ms: Iterable<AiMessage>) { messages.addAll(ms) }

    fun tool(name: String, block: AiToolDefinitionBuilder.() -> Unit) {
        tools += AiToolDefinitionBuilder(name).apply(block).build()
    }

    internal fun build(): GenerateTextRequest = GenerateTextRequest(
        model = model?.let(AiModelId::parse),
        modelPolicy = modelPolicy,
        messages = messages.toList(),
        tools = tools.toList(),
        toolChoice = toolChoice,
        temperature = temperature,
        maxTokens = maxTokens,
        topP = topP,
        stopSequences = stopSequences,
        maxToolRounds = maxToolRounds,
        metadata = metadata,
    )
}

/** Bridge so other subpackages (DefaultAiClient) can call internal build(). */
@PublishedApi
internal fun GenerateTextRequestBuilder.toRequest(): GenerateTextRequest = build()
