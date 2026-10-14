package neton.ai

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.elementDescriptors
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer

/**
 * 从 kotlinx.serialization 描述生成工具参数 / 结构化输出用的 JSON Schema（spec §10.3：受支持的子集）。
 *
 * 支持：基本类型、枚举、可空、带默认值的可选字段（不进 required）、List/Set/数组、键为字符串的 Map、嵌套类。
 * 不支持多态、上下文序列化与自定义序列化器的任意形状——遇到时抛 [IllegalArgumentException]，
 * 不静默生成一个与实际报文不符的 schema。
 *
 * [strict] 为 true 时按 OpenAI strict 结构化输出的要求生成：每个对象 `additionalProperties: false`，
 * 所有字段都进 required，可选字段改为可空。
 */
object JsonSchemas {
    inline fun <reified T> of(strict: Boolean = false): JsonObject = of(serializer<T>(), strict)

    fun of(serializer: KSerializer<*>, strict: Boolean = false): JsonObject = schema(serializer.descriptor, strict, emptySet())

    @OptIn(ExperimentalSerializationApi::class)
    private fun schema(d: SerialDescriptor, strict: Boolean, seen: Set<String>): JsonObject {
        val base = when (val kind: SerialKind = d.kind) {
            PrimitiveKind.BOOLEAN -> type("boolean")
            PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG -> type("integer")
            PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> type("number")
            PrimitiveKind.CHAR, PrimitiveKind.STRING -> type("string")
            SerialKind.ENUM -> buildJsonObject {
                put("type", "string")
                put("enum", buildJsonArray { for (i in 0 until d.elementsCount) add(JsonPrimitive(d.getElementName(i))) })
            }
            StructureKind.LIST -> buildJsonObject {
                put("type", "array")
                put("items", schema(d.getElementDescriptor(0), strict, seen))
            }
            StructureKind.MAP -> {
                val key = d.getElementDescriptor(0)
                require(key.kind == PrimitiveKind.STRING || key.kind == SerialKind.ENUM) {
                    "JSON Schema maps need string keys (${d.serialName})"
                }
                buildJsonObject {
                    put("type", "object")
                    put("additionalProperties", schema(d.getElementDescriptor(1), strict, seen))
                }
            }
            StructureKind.CLASS, StructureKind.OBJECT -> {
                require(d.serialName !in seen) { "recursive types are not supported (${d.serialName})" }
                val inner = seen + d.serialName
                val props = LinkedHashMap<String, JsonElement>()
                val required = mutableListOf<String>()
                for (i in 0 until d.elementsCount) {
                    val name = d.getElementName(i)
                    val optional = d.isElementOptional(i)
                    var s = schema(d.getElementDescriptor(i), strict, inner)
                    if (strict && optional && !d.getElementDescriptor(i).isNullable) s = nullable(s)
                    props[name] = s
                    if (strict || !optional) required += name
                }
                buildJsonObject {
                    put("type", "object")
                    put("properties", JsonObject(props))
                    if (required.isNotEmpty()) put("required", JsonArray(required.map(::JsonPrimitive)))
                    if (strict) put("additionalProperties", false)
                }
            }
            is PolymorphicKind, SerialKind.CONTEXTUAL ->
                throw IllegalArgumentException("polymorphic or contextual types are not supported in tool schemas (${d.serialName})")
            else -> throw IllegalArgumentException("unsupported kind $kind (${d.serialName})")
        }
        return if (d.isNullable) nullable(base) else base
    }

    private fun type(t: String) = buildJsonObject { put("type", t) }

    /** `type: X` → `type: [X, "null"]`；没有单一 type 的 schema 用 anyOf。 */
    private fun nullable(s: JsonObject): JsonObject {
        val t = s["type"]
        return if (t is JsonPrimitive) JsonObject(s + ("type" to buildJsonArray { add(t); add(JsonPrimitive("null")) }))
        else buildJsonObject { put("anyOf", buildJsonArray { add(s); add(buildJsonObject { put("type", "null") }) }) }
    }
}
