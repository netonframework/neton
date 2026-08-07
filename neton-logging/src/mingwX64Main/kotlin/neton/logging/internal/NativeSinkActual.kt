package neton.logging.internal

/**
 * Native actual：FileSinkNative + AsyncLogDispatcherNative。
 */
internal actual fun createFileSink(path: String, retentionDays: Int): Sink =
    FileSinkNative(path, retentionDays)

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
