package neton.ai

import neton.ai.protocol.usage.UsageCompleteness

/**
 * Token 用量。所有字段都可能为 null：各家给法不同，流式有时不给。**未知不当 0**，
 * 框架不推算 totalTokens。口径与协议层一致：[inputTokens] 含缓存读写，[outputTokens] 含推理。
 */
data class AiUsage(
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val totalTokens: Int? = null,
    val cachedInputTokens: Int? = null,
    val cacheWriteTokens: Int? = null,
    val reasoningTokens: Int? = null,
    val completeness: UsageCompleteness = UsageCompleteness.COMPLETE,
)
