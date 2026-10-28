package neton.ai.protocol.ir

import neton.ai.protocol.usage.Usage

/**
 * 结束原因。[raw] 保留上游原始字符串：编回同一协议时优先写回它。
 */
@neton.ai.ExperimentalNetonAiApi
enum class FinishReason { Stop, Length, ToolCalls, ContentFilter, Refusal, PauseTurn, Error, Other }

@neton.ai.ExperimentalNetonAiApi
data class ChatResponse(
    val id: String? = null,
    val model: String? = null,
    val content: List<Block> = emptyList(),
    val finishReason: FinishReason? = null,
    val rawFinishReason: String? = null,
    val usage: Usage? = null,
    /** 创建时间（Unix 秒）。 */
    val created: Long? = null,
    val metadata: ProviderMetadata = ProviderMetadata.EMPTY,
)
