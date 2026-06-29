@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package neton.logging.internal

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker
import neton.logging.LogLevel
import platform.posix.time
import platform.posix.usleep

private data class AsyncItem(val sinkKeys: Set<String>, val line: String, val level: LogLevel)

private fun runWriterLoop(
    sinkMap: Map<String, Sink>,
    q: AtomicReference<List<AsyncItem>>,
    run: AtomicInt,
    flushIntervalMs: Long,
    maxBatch: Int
) {
    val intervalUs = (flushIntervalMs * 1000).toInt().toUInt()
    while (run.load() != 0 || q.load().isNotEmpty()) {
        val batch = drainBatch(q, maxBatch)
        if (batch.isNotEmpty()) {
            val sinkToLines = mutableMapOf<String, MutableList<String>>()
            for (item in batch) {
                for (key in item.sinkKeys) {
                    sinkToLines.getOrPut(key) { mutableListOf() }.add(item.line)
                }
            }
            for ((key, lines) in sinkToLines) {
                sinkMap[key]?.writeLines(lines)
            }
            sinkMap.values.forEach { it.flush() }
        }
        usleep(intervalUs)
    }
    sinkMap.values.forEach { it.close() }
}

private fun drainBatch(q: AtomicReference<List<AsyncItem>>, maxBatch: Int): List<AsyncItem> {
    while (true) {
        val current = q.load()
        if (current.isEmpty()) return emptyList()
        val count = minOf(current.size, maxBatch)
        val batch = current.take(count)
        if (q.compareAndSet(current, current.drop(count))) return batch
    }
}

/**
 * Native-only 异步分发器（Phase 2 v1.2）：单 writer 线程 + 有界队列。
 * WARN/ERROR 队列满时同步 fallback；DEBUG/INFO 可丢；log.dropped 10s 窗口。
 */
internal class AsyncLogDispatcherNative(
    private val sinks: Map<String, Sink>,
    private val queueCapacity: Int = 8192,
    private val flushIntervalMs: Long = 200L,
    private val maxBatch: Int = 64,
    private val shutdownFlushTimeoutMs: Long = 2000L,
    private val droppedWarnIntervalSec: Long = 10L
) : LogDispatcher {

    private val queue = AtomicReference(emptyList<AsyncItem>())
    private val droppedCount = AtomicInt(0)
    private val fallbackWritesCount = AtomicInt(0)
    private val lastDroppedWarnTime = AtomicLong(0L)
    private val worker = Worker.start()
    private val running = AtomicInt(1)

    init {
        @Suppress("UNCHECKED_CAST")
        worker.execute(TransferMode.SAFE, { listOf(sinks, queue, running, flushIntervalMs, maxBatch) }) { args ->
            val sinkMap = args[0] as Map<String, Sink>
            val q = args[1] as AtomicReference<List<AsyncItem>>
            val run = args[2] as AtomicInt
            val intervalMs = args[3] as Long
            val batch = args[4] as Int
            runWriterLoop(sinkMap, q, run, intervalMs, batch)
        }
    }

    override fun dispatch(sinkKeys: Set<String>, line: String, level: LogLevel) {
        val neverDrop = level == LogLevel.ERROR || level == LogLevel.WARN
        if (queue.load().size >= queueCapacity) {
            if (neverDrop) {
                fallbackWritesCount.addAndFetch(1)
                while (queue.load().size >= queueCapacity && running.load() != 0) usleep(100u)
            } else {
                droppedCount.addAndFetch(1)
                maybeWarnDropped()
                return
            }
        }
        val item = AsyncItem(sinkKeys, line, level)
        while (true) {
            val current = queue.load()
            val next = current + item
            if (queue.compareAndSet(current, next)) {
                return
            }
        }
    }

    private fun maybeWarnDropped() {
        val now = time(null)
        if (now - lastDroppedWarnTime.load() >= droppedWarnIntervalSec) {
            val d = droppedCount.exchange(0)
            lastDroppedWarnTime.store(now)
            if (d > 0) {
                val ts = kotlin.time.Clock.System.now().toString()
                val warnLine = """{"ts":"$ts","level":"WARN","msg":"log.dropped","dropped":$d,"queueSize":$queueCapacity,"flushBatchSize":$maxBatch,"flushEveryMs":$flushIntervalMs}""" + "\n"
                writeDroppedWarn(warnLine)
            }
        }
    }

    private fun writeDroppedWarn(line: String) {
        sinks["stdout"]?.writeLine(line)
            ?: sinks.values.firstOrNull()?.writeLine(line)
        sinks.values.forEach { it.flush() }
    }

    override fun flush() {
        usleep((flushIntervalMs * 1000).toInt().toUInt())
    }

    override fun close() {
        running.store(0)
        var elapsed = 0L
        val intervalMs = 50L
        while (queue.load().isNotEmpty() && elapsed < shutdownFlushTimeoutMs) {
            usleep((intervalMs * 1000).toInt().toUInt())
            elapsed += intervalMs
        }
        val remaining = queue.load().size
        if (remaining > 0) {
            val ts = kotlin.time.Clock.System.now().toString()
            val errLine = """{"ts":"$ts","level":"ERROR","msg":"log.flush_timeout","remaining":$remaining,"timeoutMs":$shutdownFlushTimeoutMs}""" + "\n"
            writeDroppedWarn(errLine)
        }
        worker.requestTermination().result
    }
}
