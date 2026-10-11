package neton.logging.internal

import neton.logging.CurrentLogContext
import neton.logging.LogContext
import neton.logging.Logger
import neton.logging.LoggerFactory

/**
 * 多 Sink 路由版 LoggerFactory；contextProvider 默认 [CurrentLogContext.get]。
 */
internal class RoutingLoggerFactory(
    private val config: LoggingConfig,
    private val dispatcher: LogDispatcher,
    private val contextProvider: (() -> LogContext?)? = null
) : LoggerFactory {

    private val defaultContextProvider: () -> LogContext? = contextProvider ?: { CurrentLogContext.get() }
    private val loggerCache = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Logger>>(emptyMap())

    override fun get(name: String): Logger {
        while (true) {
            val current = loggerCache.value
            current[name]?.let { return it }
            val logger = RoutingLogger(name, config, defaultContextProvider, dispatcher)
            if (loggerCache.compareAndSet(current, current + (name to logger))) return logger
        }
    }

    override fun flush() {
        dispatcher.flush()
    }

    override fun close() {
        dispatcher.close()
    }
}
