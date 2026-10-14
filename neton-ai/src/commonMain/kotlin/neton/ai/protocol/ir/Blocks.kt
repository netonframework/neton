package neton.ai.protocol.ir

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * 有序内容块（spec §4.2）。块的顺序就是上游原始顺序，编解码与转换都必须保持。
 * 每个块都可以挂 [ProviderMetadata]，用于往返保留厂商字段。
 */
sealed interface Block {
    val metadata: ProviderMetadata
}

data class TextBlock(
    val text: String,
    /** 引用，保持来源协议的原始形状。 */
    val citations: List<JsonObject> = emptyList(),
    override val metadata: ProviderMetadata = ProviderMetadata.EMPTY,
) : Block

data class ImageBlock(
    val source: MediaSource,
    /** OpenAI `image_url.detail`。 */
    val detail: String? = null,
    override val metadata: ProviderMetadata = ProviderMetadata.EMPTY,
) : Block

/** 文档、音频、视频等非图片媒体；种类看 [MediaSource.mediaType]。 */
data class FileBlock(
    val source: MediaSource,
    val filename: String? = null,
    override val metadata: ProviderMetadata = ProviderMetadata.EMPTY,
) : Block

sealed interface MediaSource {
    val mediaType: String?

    data class Url(val url: String, override val mediaType: String? = null) : MediaSource
    data class Base64(val data: String, override val mediaType: String?) : MediaSource
    /** 厂商文件 ID（OpenAI file_id、Anthropic file_id、Gemini fileUri）。 */
    data class FileRef(val fileId: String, override val mediaType: String? = null) : MediaSource
}

/**
 * 推理内容。
 * - [signature]：Anthropic thinking 块签名；
 * - [redactedData]：Anthropic redacted_thinking 的 data（此时 [text] 为空）；
 * - [encryptedContent] 与 `openai.itemId`：Responses 的加密推理；
 * - [summary]：Responses 的推理摘要段落。
 * Gemini 的 thoughtSignature 放在 `gemini.thoughtSignature` 元数据里，不混用 [signature]。
 */
data class ReasoningBlock(
    val text: String = "",
    val signature: String? = null,
    val redactedData: String? = null,
    val encryptedContent: String? = null,
    val summary: List<String> = emptyList(),
    override val metadata: ProviderMetadata = ProviderMetadata.EMPTY,
) : Block {
    val redacted: Boolean get() = redactedData != null
}

enum class ToolKind { Function, Custom }

/** [arguments]：Function 为 JSON 文本；Custom 为原始输入文本。 */
data class ToolCallBlock(
    val id: String,
    val name: String,
    val arguments: String,
    val kind: ToolKind = ToolKind.Function,
    override val metadata: ProviderMetadata = ProviderMetadata.EMPTY,
) : Block

/** [toolName]：Gemini 的 functionResponse 按名称对应调用，其他协议可为空。 */
data class ToolResultBlock(
    val callId: String,
    val content: List<Block>,
    val isError: Boolean = false,
    val toolName: String? = null,
    override val metadata: ProviderMetadata = ProviderMetadata.EMPTY,
) : Block

/**
 * 托管工具的调用与结果（Anthropic server_tool_use / web_search_tool_result，Responses web_search_call 等），
 * 按原样保留；只能写回 [sourceFormat]，写到其他协议时丢弃并产生诊断。
 */
data class HostedToolBlock(
    val type: String,
    val raw: JsonObject,
    val sourceFormat: WireFormat,
    override val metadata: ProviderMetadata = ProviderMetadata.EMPTY,
) : Block

/**
 * IR 没有建模的块（如 Anthropic 的 text 来源 document、container_upload），原样保留，
 * 只能写回 [sourceFormat]；写到其他协议时丢弃并产生 error 级诊断。
 */
data class RawBlock(
    val type: String,
    val raw: JsonObject,
    val sourceFormat: WireFormat,
    override val metadata: ProviderMetadata = ProviderMetadata.EMPTY,
) : Block

data class RefusalBlock(
    val text: String,
    override val metadata: ProviderMetadata = ProviderMetadata.EMPTY,
) : Block

/** 块里的全部文本（只取 [TextBlock]）。 */
fun List<Block>.plainText(separator: String = ""): String =
    filterIsInstance<TextBlock>().joinToString(separator) { it.text }

internal fun JsonElement?.isJsonNullOrAbsent(): Boolean = this == null || this is kotlinx.serialization.json.JsonNull
