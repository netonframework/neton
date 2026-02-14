package neton.logging.internal

/**
 * 标准输出 Sink；line 应由调用方保证以 \n 结尾。
 */
internal class StdoutSink : Sink {
    override fun writeLine(line: String) {
        print(line)
    }
    override fun flush() {}
    override fun close() {}
}
