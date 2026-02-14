package neton.logging

import neton.logging.internal.createRoutingLoggerFactory
import neton.logging.internal.defaultLoggingConfig
import neton.logging.internal.parseLoggingConfig

/**
 * 平台默认 [LoggerFactory]：多 Sink 路由（all/error/access）；逻辑在 commonMain，Native 仅提供 createFileSink/createAsyncLogDispatcher 的 actual。
 * 供 neton-core 注入；config 由 Neton 在 startSync 时从 application.conf 加载后传入。
 *
 * @param config application.conf 的 [logging] 节（Map），null 时使用 defaultLoggingConfig。
 */
fun defaultLoggerFactory(config: Map<String, Any?>? = null): LoggerFactory {
    val c = config?.let { parseLoggingConfig(it) } ?: defaultLoggingConfig()
    return createRoutingLoggerFactory(c)
}
