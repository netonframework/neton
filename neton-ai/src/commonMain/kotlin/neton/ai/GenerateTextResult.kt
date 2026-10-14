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
    /** 每一步的明细（模型、消息、工具调用与结果、用量）。 */
    val steps: List<AiStep> = emptyList(),
    /** 交还调用方处理的工具调用：没有本地执行器，或审批挂起。 */
    val pendingToolCalls: List<AiToolCall> = emptyList(),
)
