package neton.ai

data class GenerateTextResult(
    val text: String,
    val message: AiMessage,
    val toolCalls: List<AiToolCall>,
    val toolResults: List<AiToolResult>,
    val usage: AiUsage?,
    val finishReason: AiFinishReason,
    val providerId: String,
    val modelName: String,
    val rounds: Int,
)
