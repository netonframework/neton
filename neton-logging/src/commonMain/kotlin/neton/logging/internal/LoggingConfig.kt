package neton.logging.internal

import neton.logging.LogLevel

/** 日志输出格式：默认 plain 便于开发调试，生产可配 json 便于聚合。 */
internal enum class LogFormat { PLAIN, JSON }

/**
 * Phase 2 async 配置（v1.2 冻结）。
 */
internal data class LoggingAsyncConfig(
    val enabled: Boolean = false,
    val queueSize: Int = 8192,
    val flushEveryMs: Int = 200,
    val flushBatchSize: Int = 64,
    val shutdownFlushTimeoutMs: Int = 2000,
    val droppedWarnIntervalSec: Long = 10L
)

/**
 * Native-only 多 Sink 路由配置（v1 冻结）。
 * 支持 N 条规则，每条可匹配 level（及可选 loggerName 前缀），写入 1..N 个 sink。
 */
internal data class LoggingConfig(
    val rules: List<RouteRule>,
    val minLevel: LogLevel = LogLevel.INFO,
    val format: LogFormat = LogFormat.PLAIN,
    val async: LoggingAsyncConfig = LoggingAsyncConfig()
)

internal data class RouteRule(
    val name: String,
    val levels: Set<LogLevel>,
    val loggerPrefix: String? = null,
    val msgEquals: String? = null,
    val sinks: List<SinkSpec>
)

internal sealed class SinkSpec {
    data class Stdout(val enabled: Boolean = true) : SinkSpec()
    data class File(val path: String) : SinkSpec()
}

internal fun SinkSpec.sinkKey(): String = when (this) {
    is SinkSpec.Stdout -> "stdout"
    is SinkSpec.File -> "file:$path"
}

internal fun defaultLoggingConfig(): LoggingConfig = LoggingConfig(
    rules = listOf(
        RouteRule(
            name = "all",
            levels = LogLevel.entries.toSet(),
            loggerPrefix = null,
            msgEquals = null,
            sinks = listOf(SinkSpec.Stdout(), SinkSpec.File("logs/all.log"))
        ),
        RouteRule(
            name = "error",
            levels = setOf(LogLevel.ERROR, LogLevel.WARN),
            loggerPrefix = null,
            msgEquals = null,
            sinks = listOf(SinkSpec.File("logs/error.log"))
        ),
        RouteRule(
            name = "access",
            levels = setOf(LogLevel.INFO),
            loggerPrefix = null,
            msgEquals = "http.access",
            sinks = listOf(SinkSpec.File("logs/access.log"))
        )
    ),
    minLevel = LogLevel.INFO
)
