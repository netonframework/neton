package neton.ai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonElement
import neton.ai.model.AiWarning

/**
 * 流式调用的事件。顺序：每一步以 [StepStart] 开始、[StepFinish] 结束；整次调用以恰好一个 [Finish] 或 [Error] 结束
 * （收集被取消时两者都没有）。文本、推理、工具参数都是 start / delta / end 三段，[id] 标识同一块。
 */
@neton.ai.ExperimentalNetonAiApi
sealed interface AiStreamPart {
    data class StepStart(val stepNumber: Int, val model: AiModelId, val warnings: List<AiWarning>) : AiStreamPart

    data class TextStart(val id: String) : AiStreamPart
    data class TextDelta(val id: String, val text: String) : AiStreamPart
    data class TextEnd(val id: String) : AiStreamPart

    data class ReasoningStart(val id: String) : AiStreamPart
    data class ReasoningDelta(val id: String, val text: String) : AiStreamPart
    data class ReasoningEnd(val id: String) : AiStreamPart

    /** 工具参数开始流式到达（[id] 即 toolCallId）。 */
    data class ToolInputStart(val id: String, val toolName: String) : AiStreamPart
    data class ToolInputDelta(val id: String, val delta: String) : AiStreamPart
    data class ToolInputEnd(val id: String) : AiStreamPart

    /** 完整的工具调用（已校验；[AiContent.ToolCall.invalid] 为 true 的不会执行）。 */
    data class ToolCall(val call: AiContent.ToolCall) : AiStreamPart
    data class ToolResult(val result: AiContent.ToolResult) : AiStreamPart
    data class ToolApprovalRequest(val request: AiContent.ToolApprovalRequest) : AiStreamPart
    data class ToolApprovalResponse(val response: AiContent.ToolApprovalResponse) : AiStreamPart

    data class Refusal(val text: String) : AiStreamPart
    data class Source(val source: AiContent.Source) : AiStreamPart

    /** 结构化输出的半成品（只在变化时发出）。**不是结果**，最终结果看 [Finish]。 */
    data class PartialOutput(val value: JsonElement) : AiStreamPart

    data class StepFinish(val step: AiStep) : AiStreamPart
    data class Finish(val result: AiResult) : AiStreamPart
    data class Error(val error: AiError) : AiStreamPart
}

/** 只取正文增量。 */
@neton.ai.ExperimentalNetonAiApi
fun Flow<AiStreamPart>.textDeltas(): Flow<String> = filterIsInstance<AiStreamPart.TextDelta>().map { it.text }

/** 收集到结束并返回结果；以 [AiStreamPart.Error] 结束时抛 [AiException]。 */
@neton.ai.ExperimentalNetonAiApi
suspend fun Flow<AiStreamPart>.result(): AiResult {
    var result: AiResult? = null
    var error: AiError? = null
    collect { p ->
        when (p) {
            is AiStreamPart.Finish -> result = p.result
            is AiStreamPart.Error -> error = p.error
            else -> {}
        }
    }
    error?.let { throw AiException(it) }
    return result ?: throw AiException(AiError.Unknown("stream ended without a result", null))
}
