package neton.ai.provider

import neton.ai.AiMessage
import neton.ai.AiToolDefinition
import neton.ai.ToolChoice

data class ProviderCallRequest(
    val messages: List<AiMessage>,
    val tools: List<AiToolDefinition>,
    val toolChoice: ToolChoice,
    val temperature: Double?,
    val maxTokens: Int?,
    val topP: Double?,
    val stopSequences: List<String>,
    val metadata: Map<String, String>,
)
