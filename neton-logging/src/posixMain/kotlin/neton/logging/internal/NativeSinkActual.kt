package neton.logging.internal

/**
 * Native actual：FileSinkNative + AsyncLogDispatcherNative。
 */
internal actual fun createFileSink(path: String): Sink = FileSinkNative(path)

internal actual fun createAsyncLogDispatcher(
    sinks: Map<String, Sink>,
    queueCapacity: Int,
    flushIntervalMs: Long,
    maxBatch: Int,
    shutdownFlushTimeoutMs: Long,
    droppedWarnIntervalSec: Long
): LogDispatcher = AsyncLogDispatcherNative(
    sinks = sinks,
    queueCapacity = queueCapacity,
    flushIntervalMs = flushIntervalMs,
    maxBatch = maxBatch,
    shutdownFlushTimeoutMs = shutdownFlushTimeoutMs,
    droppedWarnIntervalSec = droppedWarnIntervalSec
)
