package neton.ai.protocol.codec

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import neton.ai.protocol.ir.MediaSource
import neton.ai.protocol.ir.ProviderMetadata
import neton.ai.protocol.ir.WireFormat

/** 编解码器共用的 JSON 小工具。 */
internal object J {
    val json = Json { ignoreUnknownKeys = true }

    fun parseObject(text: String): JsonObject? = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()

    fun parseOrString(text: String): JsonElement = runCatching { json.parseToJsonElement(text) }.getOrElse { JsonPrimitive(text) }
}

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
internal fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
internal fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
internal fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
internal fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray
internal fun JsonArray.objects(): List<JsonObject> = mapNotNull { it as? JsonObject }

/** 未被 [known] 列出的字段（IR 没建模的部分），按报文命名空间保留。 */
internal fun JsonObject.extraFields(known: Set<String>): Map<String, JsonElement> = filterKeys { it !in known }

internal fun ProviderMetadata.withExtra(format: WireFormat, obj: JsonObject, known: Set<String>): ProviderMetadata =
    withWireExtra(format, obj.extraFields(known))

/** 把同协议的原始字段补回去（已写出的字段不覆盖）。 */
internal fun JsonObjectBuilder.putExtras(metadata: ProviderMetadata, format: WireFormat, written: Set<String>) {
    for ((k, v) in metadata.wireExtra(format)) if (k !in written) put(k, v)
}

/** 记录已写字段的构建器，便于最后补回原始字段时不覆盖。 */
internal class TrackingBuilder {
    private val map = LinkedHashMap<String, JsonElement>()
    val keys: Set<String> get() = map.keys

    fun put(key: String, value: JsonElement?) { if (value != null) map[key] = value }
    fun put(key: String, value: String?) { if (value != null) map[key] = JsonPrimitive(value) }
    fun put(key: String, value: Number?) { if (value != null) map[key] = JsonPrimitive(value) }
    fun put(key: String, value: Boolean?) { if (value != null) map[key] = JsonPrimitive(value) }

    fun putExtras(metadata: ProviderMetadata, format: WireFormat) {
        for ((k, v) in metadata.wireExtra(format)) if (k !in map) map[k] = v
    }

    fun build(): JsonObject = JsonObject(map)
}

internal inline fun tracking(block: TrackingBuilder.() -> Unit): JsonObject = TrackingBuilder().apply(block).build()

internal object DataUrl {
    private val PATTERN = Regex("^data:([^;,]*)(;[^,]*)?,(.*)$", RegexOption.DOT_MATCHES_ALL)

    /** data URL → Base64 来源；不是 base64 data URL 时按普通 URL 处理。 */
    fun toSource(url: String, mediaTypeHint: String? = null): MediaSource {
        val m = PATTERN.matchEntire(url)
        if (m != null && m.groupValues[2].contains("base64")) {
            return MediaSource.Base64(m.groupValues[3], m.groupValues[1].ifEmpty { mediaTypeHint })
        }
        return MediaSource.Url(url, mediaTypeHint)
    }

    fun of(source: MediaSource.Base64): String = "data:${source.mediaType ?: "application/octet-stream"};base64,${source.data}"
}

/** 当前 Unix 秒。 */
internal fun nowEpochSeconds(): Long = kotlin.time.Clock.System.now().epochSeconds

internal fun jsonOf(vararg pairs: Pair<String, JsonElement?>): JsonObject =
    JsonObject(pairs.filter { it.second != null }.associate { it.first to it.second!! })

internal fun String.json(): JsonPrimitive = JsonPrimitive(this)
