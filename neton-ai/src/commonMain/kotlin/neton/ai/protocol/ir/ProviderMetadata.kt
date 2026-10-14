package neton.ai.protocol.ir

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 按厂商命名空间隔离的元数据（spec §4.2），用于往返保留。
 *
 * 两类命名空间：
 * - 语义命名空间 [ANTHROPIC] / [GEMINI] / [OPENAI]：IR 认识、跨协议转换时会查看的字段，
 *   如 `anthropic.cacheControl`、`gemini.thoughtSignature`、`openai.itemId`；
 * - 报文命名空间 `wire:<WireFormat.id>`（[wireExtra]）：IR 没有建模的原始字段，只在编回同一协议时补回。
 */
data class ProviderMetadata(val namespaces: Map<String, JsonObject> = emptyMap()) {

    fun isEmpty(): Boolean = namespaces.isEmpty()

    operator fun get(namespace: String, key: String): JsonElement? = namespaces[namespace]?.get(key)

    fun string(namespace: String, key: String): String? = (get(namespace, key) as? JsonPrimitive)?.contentOrNull

    /** value 为 null 时删除该键；命名空间空了就整个去掉。 */
    fun with(namespace: String, key: String, value: JsonElement?): ProviderMetadata {
        val current = namespaces[namespace] ?: JsonObject(emptyMap())
        val next = if (value == null) JsonObject(current - key) else JsonObject(current + (key to value))
        return ProviderMetadata(if (next.isEmpty()) namespaces - namespace else namespaces + (namespace to next))
    }

    fun with(namespace: String, key: String, value: String?): ProviderMetadata =
        with(namespace, key, value?.let { JsonPrimitive(it) })

    /** 某协议未建模的原始字段；没有返回空对象。 */
    fun wireExtra(format: WireFormat): JsonObject = namespaces[wireNamespace(format)] ?: JsonObject(emptyMap())

    fun withWireExtra(format: WireFormat, fields: Map<String, JsonElement>): ProviderMetadata =
        if (fields.isEmpty()) ProviderMetadata(namespaces - wireNamespace(format))
        else ProviderMetadata(namespaces + (wireNamespace(format) to JsonObject(fields)))

    /** 去掉所有报文命名空间（跨协议回退时不转发前一家的原始字段，spec §4.3）。 */
    fun withoutWireExtras(): ProviderMetadata =
        ProviderMetadata(namespaces.filterKeys { !it.startsWith(WIRE_PREFIX) })

    companion object {
        val EMPTY = ProviderMetadata()

        const val ANTHROPIC = "anthropic"
        const val GEMINI = "gemini"
        const val OPENAI = "openai"

        /** Anthropic `cache_control` 原样（含 TTL）。 */
        const val CACHE_CONTROL = "cacheControl"
        /** Gemini part 上的 `thoughtSignature`。 */
        const val THOUGHT_SIGNATURE = "thoughtSignature"
        /** Responses 的 item ID。 */
        const val ITEM_ID = "itemId"

        private const val WIRE_PREFIX = "wire:"
        fun wireNamespace(format: WireFormat): String = WIRE_PREFIX + format.id
    }
}
