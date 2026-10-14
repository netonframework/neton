package neton.ai

sealed interface AiStreamEvent {
    /** First event of any stream. Emitted by AiClient AFTER first provider event read successfully. */
    data class Started(val providerId: String, val modelName: String) : AiStreamEvent

    data class TextDelta(val text: String) : AiStreamEvent

    /** 推理内容增量（模型开启推理且厂商返回推理文本时）。 */
    data class ReasoningDelta(val text: String) : AiStreamEvent

    /** Fired when tool call id+name first observed. name may be null if provider sends id first. */
    data class ToolCallStarted(val id: String, val name: String?) : AiStreamEvent

    /** Argument JSON streamed in fragments (not necessarily valid JSON pieces). */
    data class ToolCallArgumentsDelta(val id: String, val argumentsFragment: String) : AiStreamEvent

    /** Tool call fully assembled; argumentsJson is valid JSON. */
    data class ToolCallReady(val call: AiToolCall) : AiStreamEvent

    /** Local executor finished. Only emitted when an executor was registered for this tool. */
    data class ToolResultReady(val result: AiToolResult) : AiStreamEvent

    /** 一步结束（模型输出与本步工具执行都已完成）。由 AiClient 发出，provider 不得发出。 */
    data class StepFinished(val step: AiStep) : AiStreamEvent

    /** Terminal success. Exactly one Completed OR Failed per stream (or zero if cancelled). */
    data class Completed(
        val message: AiMessage,
        val text: String,
        val usage: AiUsage?,
        val finishReason: AiFinishReason,
        val steps: List<AiStep> = emptyList(),
        /** 交还调用方处理的工具调用（没有本地执行器或审批挂起）。 */
        val pendingToolCalls: List<AiToolCall> = emptyList(),
    ) : AiStreamEvent

    /** Terminal failure. */
    data class Failed(val error: AiError) : AiStreamEvent
}
