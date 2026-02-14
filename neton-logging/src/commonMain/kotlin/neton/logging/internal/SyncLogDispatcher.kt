package neton.logging.internal

import neton.logging.LogLevel

/**
 * Phase 1 同步分发器：dispatch 时直接写入 sinks，无队列。
 * enabled=false 时使用，与 Phase 2 Async 互斥。
 */
internal class SyncLogDispatcher(private val sinks: Map<String, Sink>) : LogDispatcher {

    override fun dispatch(sinkKeys: Set<String>, line: String, level: LogLevel) {
        for (key in sinkKeys) sinks[key]?.writeLine(line)
        sinks.values.forEach { it.flush() }
    }

    override fun flush() {
        sinks.values.forEach { it.flush() }
    }

    override fun close() {
        sinks.values.forEach { it.close() }
    }
}
