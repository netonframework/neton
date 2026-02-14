package neton.logging.internal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import neton.logging.Fields
import neton.logging.Logger
import neton.logging.LogContext
import neton.logging.LogLevel
import neton.logging.SensitiveFilter

/**
 * Logger 最小可运行实现：stdout 单行 JSON。
 */
internal class JsonLogger(
    private val contextProvider: (() -> LogContext?)? = null,
    private val minLevel: LogLevel = LogLevel.TRACE,
    private val output: (String) -> Unit = { println(it) }
) : Logger {

    private val json = Json { encodeDefaults = false }

    override fun trace(msg: String, fields: Fields) = log(LogLevel.TRACE, msg, fields, null)
    override fun debug(msg: String, fields: Fields) = log(LogLevel.DEBUG, msg, fields, null)
    override fun info(msg: String, fields: Fields) = log(LogLevel.INFO, msg, fields, null)
    override fun warn(msg: String, fields: Fields, cause: Throwable?) = log(LogLevel.WARN, msg, fields, cause)
    override fun error(msg: String, fields: Fields, cause: Throwable?) = log(LogLevel.ERROR, msg, fields, cause)

    private fun log(level: LogLevel, msg: String, fields: Fields, cause: Throwable?) {
        if (level.ordinal < minLevel.ordinal) return
        val obj = buildJsonObject {
            put("ts", nowUtcIso8601())
            put("level", level.name)
            put("msg", msg)
            contextProvider?.invoke()?.let { ctx ->
                put("traceId", ctx.traceId)
                ctx.spanId?.let { put("spanId", it) }
                ctx.requestId?.let { put("requestId", it) }
                ctx.userId?.let { put("userId", it) }
            }
            sanitizedFields(fields).forEach { (k, v) -> put(k, valueToJsonElement(v)) }
            cause?.let {
                put("error", it.message ?: it.toString())
                put("stackTrace", it.stackTraceToString())
            }
        }
        output(json.encodeToString(obj))
    }

    private fun sanitizedFields(fields: Fields): Map<String, Any?> {
        val sensitive = SensitiveFilter.headerKeys + SensitiveFilter.paramKeys
        return fields.mapValues { (k, v) ->
            if (sensitive.contains(k.lowercase())) REDACTED else v
        }
    }

    private fun valueToJsonElement(v: Any?): JsonElement = when (v) {
        null -> JsonNull
        is String -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        is Enum<*> -> JsonPrimitive(v.name)
        is List<*> -> JsonArray(v.map { valueToJsonElement(it) })
        is Map<*, *> -> JsonObject(
            @Suppress("UNCHECKED_CAST") (v as Map<String, Any?>).mapValues { valueToJsonElement(it.value) }
        )
        else -> JsonPrimitive(v.toString())
    }

    private fun nowUtcIso8601(): String = kotlin.time.Clock.System.now().toString()

    companion object {
        private const val REDACTED = "[REDACTED]"
    }
}
