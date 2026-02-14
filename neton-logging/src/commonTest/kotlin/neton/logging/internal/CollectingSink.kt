package neton.logging.internal

/**
 * 测试用 Sink：收集写入的行，供 Phase 2 契约测试断言。
 */
internal class CollectingSink(val lines: MutableList<String> = mutableListOf()) : Sink {
    override fun writeLine(line: String) {
        lines.add(line)
    }
    override fun writeLines(batch: List<String>) {
        lines.addAll(batch)
    }
    override fun flush() {}
    override fun close() {}
}
