package neton.ai.protocol.usage

import kotlinx.serialization.json.JsonObject
import neton.ai.protocol.ir.WireFormat

enum class UsageCompleteness {
    /** 上游给出了终态用量。 */
    COMPLETE,
    /** 流被取消或截断，只有部分用量。 */
    PARTIAL,
    /** 上游没有给出用量。 */
    UNKNOWN,
}

/** 上游原始 usage JSON 与来源协议，供应用按上游规则计费。 */
data class RawUsage(val format: WireFormat, val json: JsonObject)

/**
 * 用量事实（spec §7）。框架只记录事实，不做计费。
 *
 * 口径（与 OpenAI 一致，跨协议统一）：
 * - [inputTokens] 是全部输入，**包含** [cacheReadTokens] 与缓存写入；
 * - [outputTokens] 是全部输出，**包含** [reasoningTokens]。
 * 各协议编解码器负责与这个口径互换（如 Anthropic 的 input_tokens 不含缓存，要加回去）。
 *
 * 字段为 null 表示未知；**未知绝不当 0**。框架不推算 total。
 */
data class Usage(
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val cacheReadTokens: Long? = null,
    val cacheWrite5mTokens: Long? = null,
    val cacheWrite1hTokens: Long? = null,
    val reasoningTokens: Long? = null,
    val audioInputTokens: Long? = null,
    val audioOutputTokens: Long? = null,
    val imageTokens: Long? = null,
    val completeness: UsageCompleteness = UsageCompleteness.COMPLETE,
    val raw: RawUsage? = null,
) {
    /** 缓存写入合计；两项都未知时为 null。 */
    val cacheWriteTokens: Long?
        get() = if (cacheWrite5mTokens == null && cacheWrite1hTokens == null) null
            else (cacheWrite5mTokens ?: 0) + (cacheWrite1hTokens ?: 0)

    /**
     * 累加（spec §7）：后到的**已知**值覆盖先前的值；null 不覆盖已知值；已知的 0 是真实的 0。
     * raw 取后到的非空值；completeness 取后到的。
     */
    fun mergedWith(later: Usage): Usage = Usage(
        inputTokens = later.inputTokens ?: inputTokens,
        outputTokens = later.outputTokens ?: outputTokens,
        cacheReadTokens = later.cacheReadTokens ?: cacheReadTokens,
        cacheWrite5mTokens = later.cacheWrite5mTokens ?: cacheWrite5mTokens,
        cacheWrite1hTokens = later.cacheWrite1hTokens ?: cacheWrite1hTokens,
        reasoningTokens = later.reasoningTokens ?: reasoningTokens,
        audioInputTokens = later.audioInputTokens ?: audioInputTokens,
        audioOutputTokens = later.audioOutputTokens ?: audioOutputTokens,
        imageTokens = later.imageTokens ?: imageTokens,
        completeness = later.completeness,
        raw = later.raw ?: raw,
    )

    val hasAnyCount: Boolean
        get() = inputTokens != null || outputTokens != null || cacheReadTokens != null ||
            cacheWrite5mTokens != null || cacheWrite1hTokens != null || reasoningTokens != null

    companion object {
        val UNKNOWN = Usage(completeness = UsageCompleteness.UNKNOWN)
    }
}
