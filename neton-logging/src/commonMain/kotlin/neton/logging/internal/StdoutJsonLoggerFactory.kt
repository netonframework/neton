package neton.logging.internal

import neton.logging.CurrentLogContext
import neton.logging.LogContext
import neton.logging.LogLevel
import neton.logging.Logger
import neton.logging.LoggerFactory

/**
 * 默认 LoggerFactory 实现：返回带请求上下文注入的 JsonLogger。
 */
internal class StdoutJsonLoggerFactory(
    private val contextProvider: (() -> LogContext?)? = null,
    private val minLevel: LogLevel = LogLevel.TRACE
) : LoggerFactory {

    private val defaultContextProvider: () -> LogContext? = contextProvider
        ?: { CurrentLogContext.get() }

    private val loggerCache = mutableMapOf<String, Logger>()

    override fun get(name: String): Logger {
        return loggerCache.getOrPut(name) {
            JsonLogger(contextProvider = defaultContextProvider, minLevel = minLevel)
        }
    }
}
