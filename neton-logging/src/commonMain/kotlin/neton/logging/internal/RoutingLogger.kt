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
 * 多 Sink 路由 Logger：过滤等级 → 脱敏 → 格式化（plain/json）→ 匹配规则 → 汇总 sink 去重 → 交给 Dispatcher。
 * 默认 plain 便于开发调试；config.format=json 时输出 JSON 便于生产聚合。
 */
internal class RoutingLogger(
    private val loggerName: String,
    private val config: LoggingConfig,
    private val contextProvider: (() -> LogContext?)?,
    private val dispatcher: LogDispatcher
) : Logger {

    private val json = Json { encodeDefaults = false }

    override fun trace(msg: String, fields: Fields) = log(LogLevel.TRACE, msg, fields, null)
    override fun debug(msg: String, fields: Fields) = log(LogLevel.DEBUG, msg, fields, null)
    override fun info(msg: String, fields: Fields) = log(LogLevel.INFO, msg, fields, null)
    override fun warn(msg: String, fields: Fields, cause: Throwable?) = log(LogLevel.WARN, msg, fields, cause)
    override fun error(msg: String, fields: Fields, cause: Throwable?) = log(LogLevel.ERROR, msg, fields, cause)

    private fun log(level: LogLevel, msg: String, fields: Fields, cause: Throwable?) {
        if (level.ordinal < config.minLevel.ordinal) return
        val line = when (config.format) {
            LogFormat.PLAIN -> formatPlain(level, msg, fields, cause) + "\n"
            LogFormat.JSON -> formatJson(level, msg, fields, cause) + "\n"
        }
        val sinkKeys = resolveSinkKeys(level, msg)
        if (sinkKeys.isNotEmpty()) dispatcher.dispatch(sinkKeys, line, level)
    }

    private fun formatPlain(level: LogLevel, msg: String, fields: Fields, cause: Throwable?): String {
        val ts = nowUtcIso8601()
        val sanitized = sanitizedFields(fields)
        val ctx = contextProvider?.invoke()
        val parts = mutableListOf<String>()
        parts.add("$ts ${level.name.padEnd(5)} [$loggerName] $msg")
        if (ctx != null) {
            listOf("traceId" to ctx.traceId, "spanId" to ctx.spanId, "requestId" to ctx.requestId, "userId" to ctx.userId)
                .filter { it.second != null }
                .forEach { parts.add(" ${it.first}=${it.second}") }
        }
        if (sanitized.isNotEmpty()) {
            parts.add(" " + sanitized.entries.joinToString(" ") { "${it.key}=${it.value}" })
        }
        cause?.let {
            parts.add("\n  error: ${it.message ?: it}")
            parts.add("\n  ${it.stackTraceToString()}")
        }
        return parts.joinToString("")
    }

    private fun formatJson(level: LogLevel, msg: String, fields: Fields, cause: Throwable?): String {
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
        return json.encodeToString(obj)
    }

    private fun resolveSinkKeys(level: LogLevel, msg: String): Set<String> {
        val keys = mutableSetOf<String>()
        for (rule in config.rules) {
            if (level !in rule.levels) continue
            if (rule.loggerPrefix != null && !loggerName.startsWith(rule.loggerPrefix)) continue
            if (rule.msgEquals != null && msg != rule.msgEquals) continue
            for (spec in rule.sinks) keys.add(spec.sinkKey())
        }
        return keys
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
