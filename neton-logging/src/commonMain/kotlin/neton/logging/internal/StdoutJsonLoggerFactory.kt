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

    private val loggerCache = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Logger>>(emptyMap())

    override fun get(name: String): Logger {
        while (true) {
            val current = loggerCache.value
            current[name]?.let { return it }
            val logger = JsonLogger(contextProvider = defaultContextProvider, minLevel = minLevel)
            if (loggerCache.compareAndSet(current, current + (name to logger))) return logger
        }
    }
}
