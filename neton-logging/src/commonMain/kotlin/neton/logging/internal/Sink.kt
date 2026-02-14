package neton.logging.internal

/**
 * 日志输出 Sink（v1 冻结：仅追加、行必须以 \n 结尾）。
 * 实现层保证线程安全：FileSink 仅由 writer 线程调用。
 */
internal interface Sink {
    fun writeLine(line: String)
    fun flush()
    fun close()

    /** Phase 2 批量写：默认逐行，FileSink 可 override 为一次 write 减少 syscall。 */
    fun writeLines(batch: List<String>) {
        for (line in batch) writeLine(line)
    }
}
