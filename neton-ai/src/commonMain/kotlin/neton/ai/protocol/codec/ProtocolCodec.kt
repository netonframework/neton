package neton.ai.protocol.codec

import kotlinx.serialization.json.JsonObject
import neton.ai.protocol.diag.Diagnostics
import neton.ai.protocol.error.ProtocolError
import neton.ai.protocol.ir.ChatRequest
import neton.ai.protocol.ir.ChatResponse
import neton.ai.protocol.ir.StreamEvent
import neton.ai.protocol.ir.WireFormat
import neton.ai.protocol.transport.SseEvent

/**
 * 一种协议的编解码器：报文 ↔ IR（spec §4.1：N 对编解码器，不写 N² 个转换器）。
 *
 * 解码保留 IR 没建模的字段（`ProviderMetadata.wireExtra`），编回同一协议时补回，所以
 * `encode(decode(x))` 与 x 语义等价。编到别的协议时，目标表达不了的内容写入 [Diagnostics]。
 */
@neton.ai.ExperimentalNetonAiApi
interface ProtocolCodec {
    val format: WireFormat

    fun decodeRequest(body: JsonObject, diagnostics: Diagnostics = Diagnostics(format)): ChatRequest
    fun encodeRequest(request: ChatRequest, diagnostics: Diagnostics = Diagnostics(target = format)): JsonObject

    fun decodeResponse(body: JsonObject, diagnostics: Diagnostics = Diagnostics(format)): ChatResponse
    fun encodeResponse(response: ChatResponse, diagnostics: Diagnostics = Diagnostics(target = format)): JsonObject

    fun streamDecoder(diagnostics: Diagnostics = Diagnostics(format)): StreamDecoder
    fun streamEncoder(diagnostics: Diagnostics = Diagnostics(target = format)): StreamEncoder

    /** 协议原生的错误体。 */
    fun encodeError(error: ProtocolError): JsonObject
    /** 解析上游错误体；解析不出来时按状态码归类，原文保留在 rawBody。 */
    fun decodeError(status: Int, body: String): ProtocolError
}

/** 上游 SSE 事件 → IR 事件。上游结束后**必须**调用 [finish]（spec §6）。 */
@neton.ai.ExperimentalNetonAiApi
interface StreamDecoder {
    /** 是否已见到协议级终止标记；上游 EOF 时仍为 false 即截断（用量记 PARTIAL）。 */
    val completed: Boolean
    fun decode(event: SseEvent): List<StreamEvent>
    fun finish(): List<StreamEvent>
}

/**
 * IR 事件 → 客户端 SSE 文本。上游结束后**必须**调用 [finish]：部分协议在这一步补发终止事件与最终用量；
 * [finish] 的 `truncated` 为 true 表示上游没有给出终止事件（取消或截断）。
 */
@neton.ai.ExperimentalNetonAiApi
interface StreamEncoder {
    fun encode(event: StreamEvent): String
    fun finish(truncated: Boolean = false): String
}

@neton.ai.ExperimentalNetonAiApi
object ProtocolCodecs {
    fun of(format: WireFormat): ProtocolCodec = when (format) {
        WireFormat.OPENAI_CHAT -> OpenAiChatCodec
        WireFormat.OPENAI_RESPONSES -> OpenAiResponsesCodec
        WireFormat.ANTHROPIC -> AnthropicCodec
        WireFormat.GEMINI -> GeminiCodec
        WireFormat.BEDROCK_CONVERSE -> BedrockConverseCodec
    }
}
