package neton.ai.protocol.transport

/** 一个 SSE 事件。[event] 是 `event:` 字段（没有为 null），[data] 是多行 `data:` 以 `\n` 连接后的负载。 */
data class SseEvent(val event: String?, val data: String)

/**
 * 增量 SSE 分帧（spec §6）。
 *
 * - 事件边界 `\n\n` / `\r\n\r\n` / `\r\r` 统一归一为 `\n`；
 * - 边界或行被拆在任意 chunk 中都能续接（含跨 chunk 的 CRLF）；
 * - 多行 `data:` 按 SSE 规范以 `\n` 连接，去掉前缀与一个前导空格；
 * - 没有 data 字段的事件（注释、只有 id/retry）跳过。
 *
 * 判断 `[DONE]` 等终止标记是调用方的事。读写 socket 由宿主完成，框架只做文本处理。
 */
class SseFramer {
    private val buf = StringBuilder()
    private var pendingCr = false

    fun feed(chunk: String): List<SseEvent> {
        appendNormalized(chunk)
        val out = mutableListOf<SseEvent>()
        while (true) {
            val idx = buf.indexOf("\n\n")
            if (idx < 0) break
            val event = buf.substring(0, idx)
            buf.deleteRange(0, idx + 2)
            parse(event)?.let { out += it }
        }
        return out
    }

    /** 流结束：残留的最后一个事件没有空行结尾时也交出来（部分上游最后一帧不带空行）。 */
    fun finish(): List<SseEvent> {
        val rest = buf.toString()
        buf.clear()
        pendingCr = false
        return listOfNotNull(parse(rest.trimEnd('\n')).takeIf { rest.isNotBlank() })
    }

    private fun appendNormalized(chunk: String) {
        if (chunk.isEmpty()) return  // 空 chunk 不得消费 pendingCr，否则后续 \n 会被误当第二个换行
        var i = 0
        if (pendingCr) {
            pendingCr = false
            if (chunk[0] == '\n') i = 1
        }
        while (i < chunk.length) {
            val c = chunk[i]
            if (c == '\r') {
                buf.append('\n')
                when {
                    i + 1 >= chunk.length -> { pendingCr = true; i += 1 }
                    chunk[i + 1] == '\n' -> i += 2
                    else -> i += 1
                }
            } else {
                buf.append(c); i += 1
            }
        }
    }

    private fun parse(event: String): SseEvent? {
        val data = StringBuilder()
        var hasData = false
        var name: String? = null
        for (line in event.split('\n')) {
            when {
                line.startsWith("data:") -> {
                    hasData = true
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(fieldValue(line, 5))
                }
                line.startsWith("event:") -> name = fieldValue(line, 6)
            }
        }
        return if (hasData) SseEvent(name, data.toString()) else null
    }

    private fun fieldValue(line: String, prefix: Int): String =
        line.substring(prefix).let { if (it.startsWith(" ")) it.substring(1) else it }
}

/** SSE 输出。 */
object SseWriter {
    fun data(data: String): String = "data: $data\n\n"

    fun event(event: String, data: String): String = "event: $event\ndata: $data\n\n"
}
