@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package neton.logging.internal

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker
import neton.logging.LogLevel
import platform.posix.time
import platform.posix.usleep

private data class AsyncItem(val sinkKeys: Set<String>, val line: String, val level: LogLevel)

/**
 * 无锁多生产者单消费者队列（Michael-Scott，dummy 头节点）。
 *
 * 入队/出队都是 O(1) CAS，没有整表拷贝。这是刻意的：日志队列在真实流量下
 * 会被打满（每请求一条 access log），此前 `AtomicReference<List>` + `current + item`
 * 的写法每次入队都**拷贝整条队列**，队列钉在 8192 后 64 个请求线程全部卡在
 * 8k 元素拷贝 + CAS 重试上，整个服务活性锁死（wrk 归零、curl 超时、进程活着）。
 */
@OptIn(ExperimentalAtomicApi::class)
private class MsQueue<T : Any> {
    private class Node<T>(val value: T?) {
        val next = AtomicReference<Node<T>?>(null)
    }

    private val dummy = Node<T>(null)
    private val head = AtomicReference(dummy)
    private val tail = AtomicReference(dummy)

    fun offer(value: T) {
        val node = Node(value)
        while (true) {
            val t = tail.load()
            val next = t.next.load()
            if (t === tail.load()) {
                if (next == null) {
                    if (t.next.compareAndSet(null, node)) {
                        tail.compareAndSet(t, node)
                        return
                    }
                } else {
                    tail.compareAndSet(t, next)
                }
            }
        }
    }

    fun poll(): T? {
        while (true) {
            val h = head.load()
            val t = tail.load()
            val next = h.next.load()
            if (h === head.load()) {
                if (h === t) {
                    if (next == null) return null
                    tail.compareAndSet(t, next)
                } else {
                    val value = next!!.value!!
                    if (head.compareAndSet(h, next)) return value
                }
            }
        }
    }

    fun isEmpty(): Boolean = head.load().next.load() == null
}

private fun runWriterLoop(
    sinkMap: Map<String, Sink>,
    queue: MsQueue<AsyncItem>,
    size: AtomicInt,
    run: AtomicInt,
    flushIntervalMs: Long,
    maxBatch: Int
) {
    val idleUs = (flushIntervalMs * 1000).toInt().toUInt()
    while (true) {
        val batch = mutableListOf<AsyncItem>()
        // 忙时连续 drain 不 sleep：入队是 O(1)，writer 必须跟得上才不会积压。
        while (batch.size < maxBatch) {
            val item = queue.poll() ?: break
            size.addAndFetch(-1)
            batch.add(item)
        }
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
        if (run.load() == 0 && queue.isEmpty()) break
        // 队列空且仍在运行：按节奏休眠，避免空转烧 CPU。
        if (batch.isEmpty()) usleep(idleUs)
    }
    sinkMap.values.forEach { it.close() }
}

/**
 * Native-only 异步分发器（Phase 2 v1.2）：单 writer 线程 + 无锁队列。
 * DEBUG/INFO 队列满时可丢；WARN/ERROR 不丢（宁可短暂积压也不阻塞请求线程）；
 * log.dropped 10s 窗口。
 *
 * 硬约束：**日志永不阻塞业务线程**。旧实现队列满时 WARN/ERROR 会 usleep 自旋
 * 等空位，叠加整表拷贝入队，实测把整个服务拖到活性锁死。
 */
internal class AsyncLogDispatcherNative(
    private val sinks: Map<String, Sink>,
    private val queueCapacity: Int = 8192,
    private val flushIntervalMs: Long = 200L,
    private val maxBatch: Int = 512,
    private val shutdownFlushTimeoutMs: Long = 2000L,
    private val droppedWarnIntervalSec: Long = 10L
) : LogDispatcher {

    private val queue = MsQueue<AsyncItem>()
    private val size = AtomicInt(0)
    private val droppedCount = AtomicInt(0)
    private val lastDroppedWarnTime = AtomicLong(0L)
    private val worker = Worker.start()
    private val running = AtomicInt(1)

    init {
        @Suppress("UNCHECKED_CAST")
        worker.execute(TransferMode.SAFE, { listOf(sinks, queue, size, running, flushIntervalMs, maxBatch) }) { args ->
            val sinkMap = args[0] as Map<String, Sink>
            val q = args[1] as MsQueue<AsyncItem>
            val sz = args[2] as AtomicInt
            val run = args[3] as AtomicInt
            val intervalMs = args[4] as Long
            val batch = args[5] as Int
            runWriterLoop(sinkMap, q, sz, run, intervalMs, batch)
        }
    }

    override fun dispatch(sinkKeys: Set<String>, line: String, level: LogLevel) {
        if (running.load() == 0) return
        val neverDrop = level == LogLevel.ERROR || level == LogLevel.WARN
        if (!neverDrop && size.load() >= queueCapacity) {
            droppedCount.addAndFetch(1)
            maybeWarnDropped()
            return
        }
        queue.offer(AsyncItem(sinkKeys, line, level))
        size.addAndFetch(1)
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
        while (!queue.isEmpty() && elapsed < shutdownFlushTimeoutMs) {
            usleep((intervalMs * 1000).toInt().toUInt())
            elapsed += intervalMs
        }
        var remaining = 0
        while (queue.poll() != null) remaining++
        if (remaining > 0) {
            val ts = kotlin.time.Clock.System.now().toString()
            val errLine = """{"ts":"$ts","level":"ERROR","msg":"log.flush_timeout","remaining":$remaining,"timeoutMs":$shutdownFlushTimeoutMs}""" + "\n"
            writeDroppedWarn(errLine)
        }
        worker.requestTermination().result
    }
}
