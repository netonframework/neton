package neton.ai.internal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

internal object Jsons {
    val lenient = Json { ignoreUnknownKeys = true; isLenient = true }
    /** 结构化输出与类型化工具：未知字段、类型不符都算不合法。 */
    val strict = Json { ignoreUnknownKeys = false; explicitNulls = false; encodeDefaults = true }
}

/** 半成品 JSON 的尽力解析：补全未闭合的字符串与括号。只用于展示进度。 */
internal object PartialJson {
    fun parse(text: String): JsonElement? {
        val t = text.trim().let { if (it.startsWith("```")) it.removePrefix("```json").removePrefix("```") else it }.trim()
        if (t.isEmpty()) return null
        val stack = ArrayDeque<Char>()
        var inString = false
        var escaped = false
        for (c in t) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> stack.addLast('}')
                '[' -> stack.addLast(']')
                '}', ']' -> if (stack.isNotEmpty()) stack.removeLast()
            }
        }
        val sb = StringBuilder(t)
        if (inString) { if (escaped) sb.setLength(sb.length - 1); sb.append('"') }
        while (sb.isNotEmpty() && (sb.last() == ',' || sb.last().isWhitespace())) sb.setLength(sb.length - 1)
        if (sb.endsWith(":")) sb.append("null")
        while (stack.isNotEmpty()) sb.append(stack.removeLast())
        return runCatching { Json.parseToJsonElement(sb.toString()) }.getOrNull()
    }

    /** 文本本身就是完整、合法的 JSON。 */
    fun isComplete(text: String): Boolean = runCatching { Json.parseToJsonElement(text.trim()) }.isSuccess
}

