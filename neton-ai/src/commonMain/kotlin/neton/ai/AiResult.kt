package neton.ai

import kotlinx.serialization.json.JsonObject
import neton.ai.model.AiWarning
import neton.ai.protocol.usage.UsageCompleteness

/**
 * Token 用量（spec/ai.md §7）。口径：[inputTokens] 是全部输入（含缓存读写），[outputTokens] 是全部输出（含推理）。
 * 字段为 null 表示未知，**未知不当 0**。
 */
data class AiUsage(
    val inputTokens: Long? = null,
    val inputTokenDetails: InputDetails = InputDetails(),
    val outputTokens: Long? = null,
    val outputTokenDetails: OutputDetails = OutputDetails(),
    val completeness: UsageCompleteness = UsageCompleteness.UNKNOWN,
    /** 上游原始 usage（多步合计时丢弃）。 */
    val raw: JsonObject? = null,
) {
    data class InputDetails(val noCacheTokens: Long? = null, val cacheReadTokens: Long? = null, val cacheWriteTokens: Long? = null)
    data class OutputDetails(val textTokens: Long? = null, val reasoningTokens: Long? = null)

    /** 输入与输出都已知时的合计。 */
    val totalTokens: Long? get() = if (inputTokens != null && outputTokens != null) inputTokens + outputTokens else null

    /**
     * 多步累加：两边都未知仍为未知，否则按已知值相加；完整性取较差的一边
     * （任何一步未知或不完整，合计就不是完整的）。
     */
    operator fun plus(o: AiUsage): AiUsage = AiUsage(
        inputTokens = add(inputTokens, o.inputTokens),
        inputTokenDetails = InputDetails(
            add(inputTokenDetails.noCacheTokens, o.inputTokenDetails.noCacheTokens),
            add(inputTokenDetails.cacheReadTokens, o.inputTokenDetails.cacheReadTokens),
            add(inputTokenDetails.cacheWriteTokens, o.inputTokenDetails.cacheWriteTokens),
        ),
        outputTokens = add(outputTokens, o.outputTokens),
        outputTokenDetails = OutputDetails(
            add(outputTokenDetails.textTokens, o.outputTokenDetails.textTokens),
            add(outputTokenDetails.reasoningTokens, o.outputTokenDetails.reasoningTokens),
        ),
        completeness = maxOf(completeness, o.completeness, compareBy { it.rank }),
        raw = null,
    )

    companion object {
        val UNKNOWN = AiUsage()
        val ZERO_COMPLETE = AiUsage(completeness = UsageCompleteness.COMPLETE)
        private fun add(a: Long?, b: Long?): Long? = if (a == null && b == null) null else (a ?: 0) + (b ?: 0)
        private val UsageCompleteness.rank: Int get() = when (this) {
            UsageCompleteness.COMPLETE -> 0
            UsageCompleteness.PARTIAL -> 1
            UsageCompleteness.UNKNOWN -> 2
        }
    }
}

/** 一步的性能指标。 */
data class AiStepPerformance(
    /** 整步耗时（模型调用 + 工具执行）。 */
    val stepTimeMs: Long,
    /** 模型调用耗时（含重试）。 */
    val modelTimeMs: Long,
    /** 流式：从发出请求到第一个输出块。 */
    val timeToFirstOutputMs: Long? = null,
    /** 输出 token / 模型调用秒数；用量未知时为空。 */
    val outputTokensPerSecond: Double? = null,
    /** 每个工具调用的执行耗时（按 toolCallId）。 */
    val toolExecutionMs: Map<String, Long> = emptyMap(),
)

/** 一步：一次模型调用，以及它触发的工具执行。 */
data class AiStep(
    /** 从 1 开始。 */
    val stepNumber: Int,
    val model: AiModelId,
    /** 本步全部内容：模型输出的块，加上审批与工具结果。 */
    val content: List<AiContent>,
    val finishReason: AiFinishReason,
    val rawFinishReason: String?,
    val usage: AiUsage,
    val warnings: List<AiWarning>,
    val performance: AiStepPerformance,
    /** 本步追加到历史的消息（assistant 消息，以及有工具结果时的 tool 消息）。 */
    val responseMessages: List<AiMessage>,
) {
    val text: String get() = content.filterIsInstance<AiContent.Text>().joinToString("") { it.text }
    val reasoningText: String get() = content.filterIsInstance<AiContent.Reasoning>().joinToString("") { it.text }
    val toolCalls: List<AiToolCall> get() = content.filterIsInstance<AiContent.ToolCall>().map { it.call }
    val toolResults: List<AiContent.ToolResult> get() = content.filterIsInstance<AiContent.ToolResult>()
    val sources: List<AiContent.Source> get() = content.filterIsInstance<AiContent.Source>()
}

/**
 * 一次调用的结果。文本、结束原因等取自**最后一步**；工具调用与结果、警告、来源跨步汇总；[usage] 是全部步骤之和。
 */
data class AiResult(
    val steps: List<AiStep>,
    /** 应追加到历史的全部消息（含调用开始时处理审批产生的 tool 消息）。下一轮把它们接在原消息后面即可。 */
    val responseMessages: List<AiMessage>,
    val usage: AiUsage,
    /** 结构化输出（请求声明了 [AiRequest.output] 时）。 */
    val output: AiOutputResult<Any?>? = null,
) {
    val finalStep: AiStep? get() = steps.lastOrNull()
    val text: String get() = finalStep?.text ?: ""
    val reasoningText: String get() = finalStep?.reasoningText ?: ""
    val content: List<AiContent> get() = finalStep?.content ?: emptyList()
    val finishReason: AiFinishReason get() = finalStep?.finishReason ?: AiFinishReason.Other
    val model: AiModelId? get() = finalStep?.model
    val toolCalls: List<AiToolCall> get() = steps.flatMap { it.toolCalls }
    val toolResults: List<AiContent.ToolResult> get() = steps.flatMap { it.toolResults }
    val sources: List<AiContent.Source> get() = steps.flatMap { it.sources }
    val warnings: List<AiWarning> get() = steps.flatMap { it.warnings }

    /** 等待调用方批准的工具调用；用 [AiMessage.approvals] 答复后再调用一次即可继续。 */
    val pendingApprovals: List<AiContent.ToolApprovalRequest>
        get() = finalStep?.content.orEmpty().filterIsInstance<AiContent.ToolApprovalRequest>().filter { !it.isAutomatic }

    /** 没有本地执行器、需要调用方自己执行并回填结果的工具调用。 */
    val pendingToolCalls: List<AiToolCall>
        get() {
            val step = finalStep ?: return emptyList()
            val answered = step.toolResults.map { it.toolCallId }.toSet() + pendingApprovals.map { it.toolCallId }
            return step.content.filterIsInstance<AiContent.ToolCall>()
                .filter { !it.providerExecuted && !it.invalid && it.call.id !in answered }.map { it.call }
        }

    /** 结构化输出的值；没有成功时抛 [AiOutputException]。 */
    @Suppress("UNCHECKED_CAST")
    fun <T> requireOutput(): T = when (val o = output ?: AiOutputResult.NotGenerated("no output declared")) {
        is AiOutputResult.Success -> o.value as T
        is AiOutputResult.Refused -> throw AiOutputException(o)
        is AiOutputResult.Truncated -> throw AiOutputException(o)
        is AiOutputResult.Invalid -> throw AiOutputException(o)
        is AiOutputResult.NotGenerated -> throw AiOutputException(o)
    }
}
