package neton.ai.protocol.convert

import neton.ai.protocol.ir.*

/**
 * 媒体解析钩子（spec/ai.md §5.4）：目标协议只收内联数据（如 Bedrock Converse）而来源是 URL 时，
 * 由宿主下载并返回 base64。**框架从不自行发起网络请求**；SSRF 防护、大小上限、超时都由宿主负责。
 */
@neton.ai.ExperimentalNetonAiApi
fun interface MediaResolver {
    /** 返回内联来源；无法解析时抛出异常（转换随之失败）。 */
    suspend fun resolve(source: MediaSource.Url): MediaSource.Base64
}

@neton.ai.ExperimentalNetonAiApi
object MediaResolution {
    /** 需要内联媒体的目标协议。 */
    fun needsInline(target: WireFormat, source: MediaSource): Boolean = source is MediaSource.Url && when (target) {
        WireFormat.BEDROCK_CONVERSE -> true
        // Gemini 的 fileData 只认 Files API / GCS 的 URI，普通 http 链接要内联
        WireFormat.GEMINI -> !source.url.startsWith("gs://") && !source.url.contains("generativelanguage.googleapis.com/")
        else -> false
    }

    /** 把请求里目标协议不接受的 URL 媒体换成内联数据（同一 URL 只解析一次）。 */
    suspend fun resolve(request: ChatRequest, target: WireFormat, resolver: MediaResolver): ChatRequest {
        val cache = HashMap<String, MediaSource.Base64>()
        suspend fun src(s: MediaSource): MediaSource =
            if (s is MediaSource.Url && needsInline(target, s)) cache.getOrPut(s.url) { resolver.resolve(s) } else s
        suspend fun block(b: Block): Block = when (b) {
            is ImageBlock -> b.copy(source = src(b.source))
            is FileBlock -> b.copy(source = src(b.source))
            is ToolResultBlock -> b.copy(content = b.content.map { block(it) })
            else -> b
        }
        return request.copy(messages = request.messages.map { m -> m.copy(content = m.content.map { block(it) }) })
    }
}

/** 带媒体解析的请求转换：解码 → 解析 URL 媒体 → 编码。其余同 [ProtocolConverter.convertRequest]。 */
@neton.ai.ExperimentalNetonAiApi
suspend fun ProtocolConverter.convertRequestResolvingMedia(
    body: kotlinx.serialization.json.JsonObject,
    from: WireFormat,
    to: WireFormat,
    resolver: MediaResolver,
    policy: neton.ai.protocol.diag.LossPolicy = neton.ai.protocol.diag.LossPolicy.Allow,
    model: String? = null,
): ConversionResult<kotlinx.serialization.json.JsonObject> {
    if (from == to) return ConversionResult(body, from, to, ConversionQuality.IDENTITY, emptyList())
    val d = neton.ai.protocol.diag.Diagnostics(from, to)
    var ir = neton.ai.protocol.codec.ProtocolCodecs.of(from).decodeRequest(body, d)
    if (model != null) ir = ir.copy(model = model)
    ir = MediaResolution.resolve(ir, to, resolver)
    val out = encodeRequest(ir, to, d)
    val violations = policy.violations(d.items)
    if (violations.isNotEmpty()) throw neton.ai.protocol.diag.ConversionRejectedException(violations)
    return ConversionResult(out, from, to, quality(from, to), d.items)
}
