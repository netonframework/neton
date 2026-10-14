package neton.ai.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlin.test.assertEquals

internal fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

/** 数字统一按数值比较（1 与 1.0 等价），键顺序无关。 */
internal fun normalize(e: JsonElement): JsonElement = when (e) {
    is JsonObject -> JsonObject(e.mapValues { normalize(it.value) })
    is JsonArray -> JsonArray(e.map(::normalize))
    is JsonNull -> e
    is JsonPrimitive -> if (!e.isString && e.doubleOrNull != null) JsonPrimitive(e.doubleOrNull) else e
}

internal fun assertJsonEquivalent(expected: JsonElement, actual: JsonElement) {
    assertEquals(normalize(expected).toString(), normalize(actual).toString().let { a ->
        // 键顺序不同时给出可读的差异：按规范化后的结构比较
        if (normalize(expected) == normalize(actual)) normalize(expected).toString() else a
    })
}
