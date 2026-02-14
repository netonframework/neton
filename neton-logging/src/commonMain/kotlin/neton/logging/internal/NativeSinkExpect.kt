package neton.logging.internal

/**
 * 平台 Sink / Dispatcher 创建（expect/actual）。
 * commonMain 调用此二者；Native actual 为 FileSinkNative + AsyncLogDispatcherNative。
 */
internal expect fun createFileSink(path: String): Sink

internal expect fun createAsyncLogDispatcher(
    sinks: Map<String, Sink>,
    queueCapacity: Int,
    flushIntervalMs: Long,
    maxBatch: Int,
    shutdownFlushTimeoutMs: Long,
    droppedWarnIntervalSec: Long
): LogDispatcher
