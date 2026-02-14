package neton.logging.internal

import neton.logging.LogLevel

/**
 * 日志分发器：接收 (sinkKeys, line, level)，由实现层入队并写入对应 Sink。
 */
internal interface LogDispatcher {
    fun dispatch(sinkKeys: Set<String>, line: String, level: LogLevel)
    fun flush()
    fun close()
}
