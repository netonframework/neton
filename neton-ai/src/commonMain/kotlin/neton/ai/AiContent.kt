package neton.ai

import neton.ai.protocol.ir.MediaSource
import neton.ai.protocol.ir.ProviderMetadata

/**
 * 消息内容块（spec/ai.md §10.1：消息由**有序**内容块组成）。
 *
 * 块的顺序就是模型输出的顺序：推理 → 文本 → 工具调用可以交错，原样发回同一家厂商时必须保持
 * （Anthropic 开启 thinking 时，带工具调用的 assistant 消息缺了前面的 thinking 块会被拒绝）。
 * [ProviderMetadata] 保存厂商需要往返的字段（如 Gemini thoughtSignature），调用方通常不用关心。
 */
sealed interface AiContent {
    data class Text(val text: String, val metadata: ProviderMetadata = ProviderMetadata.EMPTY) : AiContent

    data class Image(val source: MediaSource, val detail: String? = null, val metadata: ProviderMetadata = ProviderMetadata.EMPTY) : AiContent

    /** 文档、音频等非图片媒体，种类看 [MediaSource.mediaType]。 */
    data class File(val source: MediaSource, val filename: String? = null, val metadata: ProviderMetadata = ProviderMetadata.EMPTY) : AiContent

    /**
     * 模型的推理内容。[signature]（Anthropic）、[redactedData]（Anthropic 脱敏推理）、
     * [encryptedContent]（OpenAI Responses）是厂商校验用的状态，原样发回即可，不要修改。
     */
    data class Reasoning(
        val text: String,
        val signature: String? = null,
        val redactedData: String? = null,
        val encryptedContent: String? = null,
        val metadata: ProviderMetadata = ProviderMetadata.EMPTY,
    ) : AiContent

    /** 模型拒绝回答（OpenAI refusal）。不计入 [AiMessage.text]。 */
    data class Refusal(val text: String, val metadata: ProviderMetadata = ProviderMetadata.EMPTY) : AiContent

    data class ToolCall(val call: AiToolCall, val metadata: ProviderMetadata = ProviderMetadata.EMPTY) : AiContent
}
