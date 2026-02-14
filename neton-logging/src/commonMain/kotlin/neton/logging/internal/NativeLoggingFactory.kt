package neton.logging.internal

import neton.logging.LoggerFactory

/**
 * 根据 [LoggingConfig] 构建 Sink 映射与分发器，返回 [RoutingLoggerFactory]（逻辑在 commonMain）。
 * async.enabled=true → createAsyncLogDispatcher（actual 为 Native）；否则 SyncLogDispatcher。
 */
internal fun createRoutingLoggerFactory(config: LoggingConfig): LoggerFactory {
    val sinks = buildSinksFromConfig(config)
    val dispatcher = if (config.async.enabled) {
        createAsyncLogDispatcher(
            sinks = sinks,
            queueCapacity = config.async.queueSize,
            flushIntervalMs = config.async.flushEveryMs.toLong(),
            maxBatch = config.async.flushBatchSize,
            shutdownFlushTimeoutMs = config.async.shutdownFlushTimeoutMs.toLong(),
            droppedWarnIntervalSec = config.async.droppedWarnIntervalSec
        )
    } else {
        SyncLogDispatcher(sinks)
    }
    return RoutingLoggerFactory(config, dispatcher, null)
}

/**
 * 测试用：注入自定义 sinks，用于 Phase 2 契约测试。
 */
internal fun createTestRoutingLoggerFactory(
    config: LoggingConfig,
    sinks: Map<String, Sink>,
    queueCapacity: Int = 1,
    droppedWarnIntervalSec: Long = 1L
): LoggerFactory {
    val dispatcher = createAsyncLogDispatcher(
        sinks = sinks,
        queueCapacity = queueCapacity,
        flushIntervalMs = 50L,
        maxBatch = 16,
        shutdownFlushTimeoutMs = 500L,
        droppedWarnIntervalSec = droppedWarnIntervalSec
    )
    return RoutingLoggerFactory(config, dispatcher, null)
}

private fun buildSinksFromConfig(config: LoggingConfig): Map<String, Sink> {
    val keysToSpec = mutableMapOf<String, SinkSpec>()
    for (rule in config.rules) {
        for (spec in rule.sinks) {
            if (spec is SinkSpec.Stdout && !spec.enabled) continue
            val key = spec.sinkKey()
            if (key !in keysToSpec) keysToSpec[key] = spec
        }
    }
    return keysToSpec.mapValues { (_, spec) -> spec.toSink() }
}

private fun SinkSpec.toSink(): Sink = when (this) {
    is SinkSpec.Stdout -> StdoutSink()
    is SinkSpec.File -> createFileSink(path)
}
