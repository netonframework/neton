package neton.ai.internal

import neton.ai.AiUsage

internal fun aggregateUsage(rounds: List<AiUsage?>): AiUsage? {
    val present = rounds.filterNotNull()
    if (present.isEmpty()) return null
    fun sumOrNull(selector: (AiUsage) -> Int?): Int? {
        val vals = present.mapNotNull(selector)
        return if (vals.isEmpty()) null else vals.sum()
    }
    return AiUsage(
        inputTokens = sumOrNull { it.inputTokens },
        outputTokens = sumOrNull { it.outputTokens },
        totalTokens = sumOrNull { it.totalTokens },
        cachedInputTokens = sumOrNull { it.cachedInputTokens },
        cacheWriteTokens = sumOrNull { it.cacheWriteTokens },
        reasoningTokens = sumOrNull { it.reasoningTokens },
        completeness = present.map { it.completeness }.maxByOrNull { it.ordinal } ?: neton.ai.protocol.usage.UsageCompleteness.COMPLETE,
    )
}
