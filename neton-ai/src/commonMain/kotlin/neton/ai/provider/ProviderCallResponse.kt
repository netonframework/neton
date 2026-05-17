package neton.ai.provider

import neton.ai.AiFinishReason
import neton.ai.AiMessage
import neton.ai.AiToolCall
import neton.ai.AiUsage

data class ProviderCallResponse(
    val message: AiMessage,
    val text: String,
    val toolCalls: List<AiToolCall>,
    val usage: AiUsage?,
    val finishReason: AiFinishReason,
)
