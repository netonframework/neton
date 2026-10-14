package neton.ai.protocol.endpoint

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import neton.ai.protocol.ir.WireFormat
import neton.ai.protocol.usage.RawUsage
import neton.ai.protocol.usage.Usage
import neton.ai.protocol.usage.UsageCompleteness

/** OpenAI 系的非文本端点（spec/ai.md §3.3：报文同协议透传，框架只负责用量提取）。 */
enum class Endpoint(val path: String) {
    EMBEDDINGS("/v1/embeddings"),
    IMAGES_GENERATIONS("/v1/images/generations"),
    IMAGES_EDITS("/v1/images/edits"),
    AUDIO_SPEECH("/v1/audio/speech"),
    AUDIO_TRANSCRIPTIONS("/v1/audio/transcriptions"),
    AUDIO_TRANSLATIONS("/v1/audio/translations"),
    RERANK("/v1/rerank"),
    MODERATIONS("/v1/moderations");

    companion object {
        fun fromPath(path: String): Endpoint? = entries.firstOrNull { path.substringBefore('?') == it.path }
    }
}

/**
 * 非文本端点的用量事实：token（有就给，未知为 null）加上按件计量的单位。
 * 单位名见 [UNIT_IMAGES] 等常量；没有出现的单位表示上游没给出、也推不出来，**不是 0**。
 */
data class EndpointUsage(val tokens: Usage?, val units: Map<String, Double> = emptyMap()) {
    companion object {
        const val UNIT_IMAGES = "images"
        const val UNIT_AUDIO_SECONDS = "audio_seconds"
        const val UNIT_CHARACTERS = "characters"
        const val UNIT_SEARCH_UNITS = "search_units"
        const val UNIT_DOCUMENTS = "documents"
        const val UNIT_REQUESTS = "requests"
    }
}

object EndpointUsageExtractor {
    private val json = Json { ignoreUnknownKeys = true }

    private fun obj(text: String?): JsonObject? = text?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
    private fun JsonObject.o(k: String) = this[k] as? JsonObject
    private fun JsonObject.l(k: String) = (this[k] as? JsonPrimitive)?.longOrNull
    private fun JsonObject.d(k: String) = (this[k] as? JsonPrimitive)?.doubleOrNull

    /**
     * @param requestBody JSON 请求体（multipart 请求传 null）；用于按请求计量的单位，如 TTS 的字符数。
     * @param responseBody 响应体文本；音频等二进制响应传 null。
     */
    fun extract(endpoint: Endpoint, requestBody: String?, responseBody: String?): EndpointUsage {
        val req = obj(requestBody)
        val res = obj(responseBody)
        val usage = res?.o("usage")
        val units = LinkedHashMap<String, Double>()
        val tokens: Usage? = when (endpoint) {
            Endpoint.EMBEDDINGS -> usage?.let {
                val input = it.l("prompt_tokens") ?: it.l("total_tokens")
                Usage(inputTokens = input, outputTokens = null, raw = RawUsage(WireFormat.OPENAI_CHAT, it))
            }
            Endpoint.IMAGES_GENERATIONS, Endpoint.IMAGES_EDITS -> {
                (res?.get("data") as? JsonArray)?.let { units[EndpointUsage.UNIT_IMAGES] = it.size.toDouble() }
                usage?.let {
                    Usage(inputTokens = it.l("input_tokens"), outputTokens = it.l("output_tokens"),
                        imageTokens = it.o("input_tokens_details")?.l("image_tokens"), raw = RawUsage(WireFormat.OPENAI_CHAT, it))
                }
            }
            Endpoint.AUDIO_SPEECH -> {
                // TTS 按输入字符计价；响应是音频，没有 usage
                (req?.get("input") as? JsonPrimitive)?.contentOrNull?.let { s ->
                    units[EndpointUsage.UNIT_CHARACTERS] = codePoints(s).toDouble()
                }
                null
            }
            Endpoint.AUDIO_TRANSCRIPTIONS, Endpoint.AUDIO_TRANSLATIONS -> {
                // usage 两种形状：{type:"tokens", input_tokens, output_tokens} 或 {type:"duration", seconds}
                val seconds = usage?.takeIf { (it["type"] as? JsonPrimitive)?.contentOrNull == "duration" }?.d("seconds") ?: res?.d("duration")
                seconds?.let { units[EndpointUsage.UNIT_AUDIO_SECONDS] = it }
                usage?.takeIf { it.l("input_tokens") != null || it.l("output_tokens") != null }?.let {
                    Usage(inputTokens = it.l("input_tokens"), outputTokens = it.l("output_tokens"),
                        audioInputTokens = it.o("input_token_details")?.l("audio_tokens"), raw = RawUsage(WireFormat.OPENAI_CHAT, it))
                }
            }
            Endpoint.RERANK -> {
                // Cohere 形状 meta.billed_units.search_units；Jina 等给 usage.total_tokens
                res?.o("meta")?.o("billed_units")?.d("search_units")?.let { units[EndpointUsage.UNIT_SEARCH_UNITS] = it }
                (req?.get("documents") as? JsonArray)?.let { units[EndpointUsage.UNIT_DOCUMENTS] = it.size.toDouble() }
                usage?.let { Usage(inputTokens = it.l("total_tokens") ?: it.l("prompt_tokens"), raw = RawUsage(WireFormat.OPENAI_CHAT, it)) }
            }
            Endpoint.MODERATIONS -> { units[EndpointUsage.UNIT_REQUESTS] = 1.0; null }
        }
        return EndpointUsage(tokens?.takeIf { it.hasAnyCount } ?: tokens?.copy(completeness = UsageCompleteness.UNKNOWN), units)
    }

    private fun codePoints(s: String): Int {
        var n = 0
        var i = 0
        while (i < s.length) {
            if (s[i].isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) i += 2 else i++
            n++
        }
        return n
    }
}
