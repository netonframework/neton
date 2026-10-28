package neton.ai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * 工具参数的 JSON Schema 校验（常用子集）：type（含类型数组与 null）、required、properties、
 * additionalProperties（false 或 schema）、enum、const、items、minItems/maxItems、minimum/maximum、
 * minLength/maxLength、anyOf/oneOf。不认识的关键字忽略（放行），不做 $ref 解析。
 */
@neton.ai.ExperimentalNetonAiApi
object JsonSchemaValidator {
    /** 返回第一个错误的说明（带路径）；合法返回 null。 */
    fun validate(schema: JsonObject, value: JsonElement): String? = check(schema, value, "$")

    private fun check(s: JsonObject, v: JsonElement, path: String): String? {
        (s["anyOf"] as? JsonArray ?: s["oneOf"] as? JsonArray)?.let { alts ->
            val ok = alts.any { (it as? JsonObject)?.let { a -> check(a, v, path) == null } ?: false }
            if (!ok) return "$path does not match any allowed schema"
        }
        (s["enum"] as? JsonArray)?.let { if (v !in it) return "$path must be one of $it" }
        s["const"]?.let { if (v != it) return "$path must be $it" }
        val types = when (val t = s["type"]) {
            is JsonPrimitive -> listOfNotNull(t.contentOrNull)
            is JsonArray -> t.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            else -> emptyList()
        }
        if (types.isNotEmpty() && types.none { matches(it, v) }) return "$path must be ${types.joinToString(" or ")}"
        when (v) {
            is JsonObject -> {
                (s["required"] as? JsonArray)?.forEach { r -> (r as? JsonPrimitive)?.contentOrNull?.let { if (it !in v) return "$path.$it is required" } }
                val props = s["properties"] as? JsonObject ?: JsonObject(emptyMap())
                for ((k, child) in v) {
                    val ps = props[k] as? JsonObject
                    if (ps != null) check(ps, child, "$path.$k")?.let { return it }
                    else when (val ap = s["additionalProperties"]) {
                        is JsonPrimitive -> if (ap.booleanOrNull == false) return "$path.$k is not allowed"
                        is JsonObject -> check(ap, child, "$path.$k")?.let { return it }
                        else -> {}
                    }
                }
            }
            is JsonArray -> {
                (s["minItems"] as? JsonPrimitive)?.longOrNull?.let { if (v.size < it) return "$path needs at least $it items" }
                (s["maxItems"] as? JsonPrimitive)?.longOrNull?.let { if (v.size > it) return "$path allows at most $it items" }
                (s["items"] as? JsonObject)?.let { item -> v.forEachIndexed { i, e -> check(item, e, "$path[$i]")?.let { return it } } }
            }
            is JsonPrimitive -> {
                if (v.isString) {
                    val len = v.content.length
                    (s["minLength"] as? JsonPrimitive)?.longOrNull?.let { if (len < it) return "$path is shorter than $it" }
                    (s["maxLength"] as? JsonPrimitive)?.longOrNull?.let { if (len > it) return "$path is longer than $it" }
                } else v.doubleOrNull?.let { d ->
                    (s["minimum"] as? JsonPrimitive)?.doubleOrNull?.let { if (d < it) return "$path must be >= $it" }
                    (s["maximum"] as? JsonPrimitive)?.doubleOrNull?.let { if (d > it) return "$path must be <= $it" }
                }
            }
        }
        return null
    }

    private fun matches(type: String, v: JsonElement): Boolean = when (type) {
        "object" -> v is JsonObject
        "array" -> v is JsonArray
        "string" -> v is JsonPrimitive && v.isString
        "null" -> v is JsonNull
        "boolean" -> v is JsonPrimitive && !v.isString && v.booleanOrNull != null
        "integer" -> v is JsonPrimitive && !v.isString && v.longOrNull != null
        "number" -> v is JsonPrimitive && !v.isString && v.doubleOrNull != null
        else -> true
    }
}
