package neton.ai.usage

import neton.ai.AiFinishReason
import neton.ai.AiUsage

data class AiUsageEvent(
    val requestId: String?,
    val providerId: String,
    val modelName: String,
    val usage: AiUsage?,
    val round: Int,
    val requestMetadata: Map<String, String>,
    val timestampEpochMillis: Long,
    val durationMillis: Long,
    val finishReason: AiFinishReason?,
    val success: Boolean,
    val errorKind: String? = null,
)
