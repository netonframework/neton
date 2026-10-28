package neton.ai

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import neton.ai.protocol.ir.MediaSource
import neton.ai.protocol.ir.ProviderMetadata

/**
 * 消息内容块（spec/ai.md §10.1：消息由**有序**内容块组成）。
 *
 * 块的顺序就是模型输出的顺序：推理、文本与工具调用可以交错，原样发回同一家厂商时必须保持
 * （Anthropic 开启 thinking 时，带工具调用的 assistant 消息缺了前面的 thinking 块会被拒绝）。
 * [ProviderMetadata] 保存需要往返的厂商字段（如 Gemini thoughtSignature），调用方通常不用关心。
 */
@neton.ai.ExperimentalNetonAiApi
sealed interface AiContent {
    data class Text(
        val text: String,
        /** 模型给出的引用（结果中的 citations 原样保留）。 */
        val citations: List<JsonObject> = emptyList(),
        val metadata: ProviderMetadata = ProviderMetadata.EMPTY,
    ) : AiContent

    data class Image(val source: MediaSource, val detail: String? = null, val metadata: ProviderMetadata = ProviderMetadata.EMPTY) : AiContent

    /** 文档、音频、视频等非图片媒体，种类看 [MediaSource.mediaType]。 */
    data class File(val source: MediaSource, val filename: String? = null, val metadata: ProviderMetadata = ProviderMetadata.EMPTY) : AiContent

    /**
     * 模型的推理内容。[signature]（Anthropic）、[redactedData]（脱敏推理）、[encryptedContent]（OpenAI Responses）
     * 是厂商校验用的状态，原样发回即可，不要修改。
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

    /**
     * 工具调用。[invalid] 为 true 表示调用没能通过校验（未知工具或参数不合法，且修复失败）：
     * 不会执行，结果是一条错误输出，模型可以据此改正。
     */
    data class ToolCall(
        val call: AiToolCall,
        val metadata: ProviderMetadata = ProviderMetadata.EMPTY,
        val providerExecuted: Boolean = false,
        val invalid: Boolean = false,
    ) : AiContent

    /** 工具结果（role=Tool 的消息里；厂商托管工具的结果在 assistant 消息里）。 */
    data class ToolResult(
        val toolCallId: String,
        val toolName: String,
        val output: AiToolOutput,
        /** 执行器先行发出的阶段性结果；最终结果之前可能有多个。只出现在流里，不进历史。 */
        val preliminary: Boolean = false,
        val metadata: ProviderMetadata = ProviderMetadata.EMPTY,
    ) : AiContent

    /**
     * 工具调用等待批准（assistant 消息里）。[isAutomatic] 为 true 表示由审批策略自动做出了决定，
     * 后面紧跟对应的 [ToolApprovalResponse]。[signature] 是宿主配置了审批密钥时的签名，用于防篡改。
     */
    data class ToolApprovalRequest(
        val approvalId: String,
        val toolCallId: String,
        val reason: String? = null,
        val isAutomatic: Boolean = false,
        val signature: String? = null,
    ) : AiContent

    /** 对审批请求的答复（role=Tool 的消息里）。调用方把它作为最后一条消息发回即可继续。 */
    data class ToolApprovalResponse(val approvalId: String, val approved: Boolean, val reason: String? = null) : AiContent

    /**
     * SDK 没有建模的块（如 Anthropic web search 的 server_tool_use 与检索结果、厂商私有块），
     * 原样保留，发回同一家厂商时写回，其他厂商丢弃。
     */
    data class Opaque(val block: neton.ai.protocol.ir.Block) : AiContent

    /** 模型引用的来源（只出现在结果里，不会发回模型）。 */
    data class Source(val id: String, val url: String? = null, val title: String? = null, val mediaType: String? = null) : AiContent
}

/** 工具调用：[argumentsJson] 是模型给出的原始参数文本，[input] 是解析后的值（解析失败为空对象）。 */
@neton.ai.ExperimentalNetonAiApi
data class AiToolCall(val id: String, val name: String, val argumentsJson: String) {
    val input: JsonElement by lazy {
        runCatching { neton.ai.internal.Jsons.lenient.parseToJsonElement(argumentsJson.ifBlank { "{}" }) }.getOrElse { JsonObject(emptyMap()) }
    }
}
