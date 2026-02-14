package neton.logging.internal

import neton.logging.LogLevel

/**
 * 从 application.conf 的 [logging] 节解析 LoggingConfig（Phase 1 冻结）。
 * 支持 [logging] + [[logging.sinks]] 结构。
 * v1.1：仅从 application.conf 读取，不与 ConfigLoader 职责冲突。
 *
 * 示例 application.conf：
 * ```toml
 * [logging]
 * level = "INFO"
 *
 * [[logging.sinks]]
 * name = "access"
 * file = "logs/access.log"
 * levels = "INFO"
 * route = "http.access"
 *
 * [[logging.sinks]]
 * name = "error"
 * file = "logs/error.log"
 * levels = "ERROR,WARN"
 *
 * [[logging.sinks]]
 * name = "all"
 * file = "logs/all.log"
 * levels = "ALL"
 * ```
 *
 * stdout 规则：有 sinks 配置时默认关闭；需 stdout 时显式加 name=stdout 的 sink。
 */
internal fun parseLoggingConfig(configMap: Map<String, Any?>?): LoggingConfig? {
    if (configMap == null) return null
    val levelStr = (configMap["level"] as? String)?.uppercase() ?: "INFO"
    val minLevel = LogLevel.entries.find { it.name == levelStr } ?: LogLevel.INFO
    val formatStr = (configMap["format"] as? String)?.uppercase() ?: "PLAIN"
    val format = when (formatStr) {
        "JSON" -> LogFormat.JSON
        else -> LogFormat.PLAIN
    }

    @Suppress("UNCHECKED_CAST")
    val asyncRaw = configMap["async"] as? Map<String, Any?>
    val async = parseAsyncConfig(asyncRaw)

    @Suppress("UNCHECKED_CAST")
    val sinksRaw = configMap["sinks"] as? List<Map<String, Any?>>
    if (sinksRaw.isNullOrEmpty()) return null

    val rules = mutableListOf<RouteRule>()
    var hasStdout = false

    for (sink in sinksRaw) {
        val name = sink["name"] as? String ?: continue
        val file = sink["file"] as? String ?: sink["path"] as? String
        val levelsStr = when (val raw = sink["levels"]) {
            is String -> raw
            is List<*> -> raw.joinToString(",") { it?.toString() ?: "" }
            else -> null
        }
        val route = sink["route"] as? String

        val levels = when {
            levelsStr.isNullOrBlank() -> LogLevel.entries.toSet()
            levelsStr.uppercase().contains("ALL") -> LogLevel.entries.toSet()
            else -> levelsStr.split(",").mapNotNull { s ->
                LogLevel.entries.find { it.name == s.trim().uppercase() }
            }.toSet().takeIf { it.isNotEmpty() } ?: LogLevel.entries.toSet()
        }

        when {
            name.equals("stdout", ignoreCase = true) || file == null -> {
                if (!hasStdout) {
                    rules.add(RouteRule(name = "stdout", levels = LogLevel.entries.toSet(), msgEquals = null, sinks = listOf(SinkSpec.Stdout())))
                    hasStdout = true
                }
            }
            else -> {
                val msgEquals = if (route.isNullOrEmpty()) null else route
                rules.add(RouteRule(name = name, levels = levels, msgEquals = msgEquals, sinks = listOf(SinkSpec.File(file))))
            }
        }
    }

    // v1.1 冻结：有 sinks 配置时 stdout 默认关闭（避免双写 IO）；需 stdout 时显式加 name=stdout 的 sink
    return LoggingConfig(rules = rules, minLevel = minLevel, format = format, async = async)
}

private fun parseAsyncConfig(m: Map<String, Any?>?): LoggingAsyncConfig {
    if (m == null) return LoggingAsyncConfig()
    return LoggingAsyncConfig(
        enabled = (m["enabled"] as? Boolean) ?: false,
        queueSize = (m["queueSize"] as? Number)?.toInt() ?: 8192,
        flushEveryMs = (m["flushEveryMs"] as? Number)?.toInt() ?: 200,
        flushBatchSize = (m["flushBatchSize"] as? Number)?.toInt() ?: 64,
        shutdownFlushTimeoutMs = (m["shutdownFlushTimeoutMs"] as? Number)?.toInt() ?: 2000,
        droppedWarnIntervalSec = (m["droppedWarnIntervalSec"] as? Number)?.toLong() ?: 10L
    )
}
