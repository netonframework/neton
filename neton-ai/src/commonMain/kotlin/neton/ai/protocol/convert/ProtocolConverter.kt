package neton.ai.protocol.convert

import kotlinx.serialization.json.JsonObject
import neton.ai.protocol.codec.GeminiCodec
import neton.ai.protocol.codec.GeminiEncodeOptions
import neton.ai.protocol.codec.ProtocolCodecs
import neton.ai.protocol.diag.ConversionRejectedException
import neton.ai.protocol.diag.Diagnostic
import neton.ai.protocol.diag.Diagnostics
import neton.ai.protocol.diag.LossPolicy
import neton.ai.protocol.error.ProtocolError
import neton.ai.protocol.ir.ChatRequest
import neton.ai.protocol.ir.ChatResponse
import neton.ai.protocol.ir.StreamEvent
import neton.ai.protocol.ir.WireFormat
import neton.ai.protocol.transport.SseFramer
import neton.ai.protocol.usage.Usage
import neton.ai.protocol.usage.UsageCompleteness

/** 转换质量（spec §5.1）。 */
enum class ConversionQuality { IDENTITY, GOOD, FAIR }

data class ConversionResult<T>(
    val value: T,
    val source: WireFormat,
    val target: WireFormat,
    val quality: ConversionQuality,
    val diagnostics: List<Diagnostic>,
)

/**
 * 跨协议转换入口（spec §5）：源报文 → IR → 目标报文，同协议恒等透传、不经过 IR（spec §4.1）。
 *
 * 需要在 IR 上做宿主处理（如网关的签名状态引用）时，传 `transform`；同协议时 transform 不会被调用。
 */
object ProtocolConverter {

    fun quality(from: WireFormat, to: WireFormat): ConversionQuality = when {
        from == to -> ConversionQuality.IDENTITY
        setOf(from, to) == setOf(WireFormat.OPENAI_CHAT, WireFormat.OPENAI_RESPONSES) -> ConversionQuality.GOOD
        else -> ConversionQuality.FAIR
    }

    /**
     * 请求转换。损耗策略只作用于请求阶段（spec §5.3）：违反策略时抛 [ConversionRejectedException]。
     * @param model 目标模型名（Gemini 报文里没有模型名，签名诊断按它判断型号）。
     */
    fun convertRequest(
        body: JsonObject,
        from: WireFormat,
        to: WireFormat,
        policy: LossPolicy = LossPolicy.Allow,
        model: String? = null,
        gemini: GeminiEncodeOptions = GeminiEncodeOptions(),
        transform: ((ChatRequest) -> ChatRequest)? = null,
    ): ConversionResult<JsonObject> {
        if (from == to) return ConversionResult(body, from, to, ConversionQuality.IDENTITY, emptyList())
        val d = Diagnostics(from, to)
        var ir = ProtocolCodecs.of(from).decodeRequest(body, d)
        if (model != null) ir = ir.copy(model = model)
        transform?.let { ir = it(ir) }
        val out = encodeRequest(ir, to, d, gemini)
        val violations = policy.violations(d.items)
        if (violations.isNotEmpty()) throw ConversionRejectedException(violations)
        return ConversionResult(out, from, to, quality(from, to), d.items)
    }

    /** IR → 目标报文（Gemini 带签名选项）。 */
    fun encodeRequest(ir: ChatRequest, to: WireFormat, d: Diagnostics, gemini: GeminiEncodeOptions = GeminiEncodeOptions()): JsonObject =
        if (to == WireFormat.GEMINI) GeminiCodec.encodeRequest(ir, d, gemini) else ProtocolCodecs.of(to).encodeRequest(ir, d)

    /** 非流式响应转换；三种策略下都完成转换并返回诊断。 */
    fun convertResponse(
        body: JsonObject,
        from: WireFormat,
        to: WireFormat,
        transform: ((ChatResponse) -> ChatResponse)? = null,
    ): ConversionResult<JsonObject> {
        if (from == to) return ConversionResult(body, from, to, ConversionQuality.IDENTITY, emptyList())
        val d = Diagnostics(from, to)
        var ir = ProtocolCodecs.of(from).decodeResponse(body, d)
        transform?.let { ir = it(ir) }
        return ConversionResult(ProtocolCodecs.of(to).encodeResponse(ir, d), from, to, quality(from, to), d.items)
    }

    /**
     * 上游错误 → 客户端协议的错误体（spec §8）：同协议原样透传，跨协议按客户端协议重新渲染。
     * 返回（HTTP 状态码, 错误体）。
     */
    fun convertError(status: Int, body: String, from: WireFormat, to: WireFormat): Pair<Int, String> {
        if (from == to) return status to body
        val err = ProtocolCodecs.of(from).decodeError(status, body)
        return status to ProtocolCodecs.of(to).encodeError(err).toString()
    }

    fun renderError(error: ProtocolError, to: WireFormat): String = ProtocolCodecs.of(to).encodeError(error).toString()

    /** @param defaultModel 上游流里没有模型名时，输出用这个名字。 */
    fun streamConverter(
        from: WireFormat,
        to: WireFormat,
        transform: ((StreamEvent) -> List<StreamEvent>)? = null,
        defaultModel: String? = null,
    ): StreamConverter = StreamConverter(from, to, transform, defaultModel)
}

/**
 * 一条流的转换状态（spec §6）。上游 SSE 文本按任意切分喂入 [feed]，上游结束后**必须**调用 [finish]。
 *
 * 同协议时原样透传，同时解码一遍以记录用量与终止状态（便于宿主统一计量）。
 */
class StreamConverter internal constructor(
    val from: WireFormat,
    val to: WireFormat,
    private val transform: ((StreamEvent) -> List<StreamEvent>)?,
    private val defaultModel: String? = null,
) {
    private var startSeen = false
    private val diagnostics = Diagnostics(from, to)
    private val framer = SseFramer()
    private val decoder = ProtocolCodecs.of(from).streamDecoder(diagnostics)
    private val encoder = if (from == to) null else ProtocolCodecs.of(to).streamEncoder(diagnostics)
    private var accumulated: Usage? = null
    private var finished = false

    val diagnosticItems: List<Diagnostic> get() = diagnostics.items

    /** 是否见到了上游的协议级终止标记。 */
    val completed: Boolean get() = decoder.completed

    /**
     * 累计用量。上游没给出任何用量为 [UsageCompleteness.UNKNOWN]；[finish] 时上游未正常结束则记为 PARTIAL。
     */
    val usage: Usage
        get() {
            val u = accumulated ?: return Usage.UNKNOWN
            return if (finished && !decoder.completed) u.copy(completeness = UsageCompleteness.PARTIAL) else u
        }

    fun feed(chunk: String): String {
        val sb = StringBuilder()
        for (ev in framer.feed(chunk)) handle(decoder.decode(ev), sb)
        return if (encoder == null) chunk else sb.toString()
    }

    /**
     * 上游结束。[truncated] 为 null 时按是否见到上游终止标记判断；宿主已经自行判定（如只在确认完整时
     * 才调用本方法）时显式传入。截断时不输出正常结束事件，客户端据此知道结果不完整。
     */
    fun finish(truncated: Boolean? = null): String {
        if (finished) return ""
        val sb = StringBuilder()
        for (ev in framer.finish()) handle(decoder.decode(ev), sb)
        handle(decoder.finish(), sb)
        finished = true
        encoder?.let { sb.append(it.finish(truncated = truncated ?: !decoder.completed)) }
        return if (encoder == null) "" else sb.toString()
    }

    private fun handle(events: List<StreamEvent>, sb: StringBuilder) {
        for (e1 in events) {
            val e0 = if (e1 is StreamEvent.MessageStart && e1.model == null && defaultModel != null) e1.copy(model = defaultModel) else e1
            if (!startSeen && encoder != null) {
                startSeen = true
                if (e0 !is StreamEvent.MessageStart) encoder.encode(StreamEvent.MessageStart(model = defaultModel)).let(sb::append)
            }
            val list = if (transform != null && encoder != null) transform.invoke(e0) else listOf(e0)
            for (e in list) {
                if (e is StreamEvent.UsageUpdate) accumulated = accumulated?.mergedWith(e.usage) ?: e.usage
                encoder?.let { sb.append(it.encode(e)) }
            }
        }
    }
}
