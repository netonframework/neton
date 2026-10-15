package neton.ai.telemetry

import kotlinx.serialization.json.JsonElement
import neton.ai.AiContent
import neton.ai.AiError
import neton.ai.AiFinishReason
import neton.ai.AiMessage
import neton.ai.AiModelId
import neton.ai.AiToolOutput
import neton.ai.AiUsage

/**
 * 遥测集成（spec/ai.md §10.5）：客户端级，接收每次调用的生命周期事件，用来接追踪、指标或审计。
 *
 * 事件是 neton-ai 自己的类型，不绑定任何观测标准的字段命名；按 OpenTelemetry GenAI 语义约定导出 span 的
 * 是独立适配器 [GenAiSpans]。集成里的异常被隔离（只记日志），不影响调用。
 *
 * 默认不记录内容：只有 [AiTelemetrySettings.recordInputs] / [AiTelemetrySettings.recordOutputs] 打开时，
 * 事件里才带提示词、工具参数、模型输出与工具结果；凭证与请求头从不进入事件。
 */
fun interface AiTelemetry {
    suspend fun onEvent(event: AiTelemetryEvent)
}

data class AiTelemetrySettings(
    /** 事件里带上输入（消息、工具参数）。 */
    val recordInputs: Boolean = false,
    /** 事件里带上输出（模型输出内容、工具结果）。 */
    val recordOutputs: Boolean = false,
)

/** 被观测的操作。 */
enum class AiOperation(val id: String) {
    Generate("generate"), Stream("stream"), Embed("embed"), Image("image"),
    Speech("speech"), Transcribe("transcribe"), Rerank("rerank"),
}

/**
 * 一次操作的上下文，同一操作的所有事件共享。[operationId] 在进程内唯一；
 * [functionId] 与 [metadata] 来自请求（[metadata] 里的 `traceparent` 会被 [GenAiSpans] 用作父 span）。
 */
data class AiTelemetryContext(
    val operationId: String,
    val operation: AiOperation,
    val functionId: String?,
    val metadata: Map<String, String>,
)

sealed interface AiTelemetryEvent {
    val context: AiTelemetryContext

    data class OperationStart(
        override val context: AiTelemetryContext,
        val model: AiModelId?,
        /** 仅在记录输入时给出。 */
        val input: List<AiMessage>? = null,
    ) : AiTelemetryEvent

    data class StepStart(override val context: AiTelemetryContext, val stepNumber: Int, val model: AiModelId) : AiTelemetryEvent

    data class ModelCallStart(override val context: AiTelemetryContext, val stepNumber: Int, val model: AiModelId, val streaming: Boolean) : AiTelemetryEvent

    /** 模型调用失败后将重试（[attempt] 从 1 起，表示第几次重试）。 */
    data class ModelCallRetry(
        override val context: AiTelemetryContext,
        val stepNumber: Int,
        val model: AiModelId,
        val attempt: Int,
        val error: AiError,
        val delayMs: Long,
    ) : AiTelemetryEvent

    data class ModelCallEnd(
        override val context: AiTelemetryContext,
        val stepNumber: Int,
        val model: AiModelId,
        val durationMs: Long,
        val timeToFirstOutputMs: Long?,
        val responseId: String?,
        val finishReason: AiFinishReason,
        val rawFinishReason: String?,
        val usage: AiUsage,
        /** 仅在记录输出时给出。 */
        val output: List<AiContent>? = null,
    ) : AiTelemetryEvent

    data class ModelCallError(
        override val context: AiTelemetryContext,
        val stepNumber: Int,
        val model: AiModelId,
        val durationMs: Long,
        val error: AiError,
    ) : AiTelemetryEvent

    /** 首个候选模型在任何输出之前失败，换下一个候选。 */
    data class Fallback(override val context: AiTelemetryContext, val from: AiModelId, val error: AiError) : AiTelemetryEvent

    data class ToolStart(
        override val context: AiTelemetryContext,
        val stepNumber: Int,
        val toolCallId: String,
        val toolName: String,
        /** 仅在记录输入时给出。 */
        val input: JsonElement? = null,
    ) : AiTelemetryEvent

    data class ToolEnd(
        override val context: AiTelemetryContext,
        val stepNumber: Int,
        val toolCallId: String,
        val toolName: String,
        val durationMs: Long,
        /** 工具报告失败、被拒绝或超时。 */
        val isError: Boolean,
        /** 仅在记录输出时给出。 */
        val output: AiToolOutput? = null,
    ) : AiTelemetryEvent

    data class StepEnd(
        override val context: AiTelemetryContext,
        val stepNumber: Int,
        val finishReason: AiFinishReason,
        val usage: AiUsage,
    ) : AiTelemetryEvent

    data class OperationEnd(
        override val context: AiTelemetryContext,
        val durationMs: Long,
        val usage: AiUsage,
        val steps: Int,
        val finishReason: AiFinishReason?,
    ) : AiTelemetryEvent

    data class OperationError(override val context: AiTelemetryContext, val durationMs: Long, val error: AiError) : AiTelemetryEvent
}
