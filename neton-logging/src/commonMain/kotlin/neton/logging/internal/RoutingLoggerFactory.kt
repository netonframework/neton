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
    private val loggerCache = mutableMapOf<String, Logger>()

    override fun get(name: String): Logger {
        return loggerCache.getOrPut(name) {
            RoutingLogger(name, config, defaultContextProvider, dispatcher)
        }
    }

    /** 供测试关闭 dispatcher（drain + flush）。 */
    internal fun close() {
        dispatcher.close()
    }
}
