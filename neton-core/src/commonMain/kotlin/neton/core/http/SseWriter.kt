package neton.core.http

/**
 * Server-Sent Events 写出器。
 * event()/comment() 按 SSE 规范格式化；raw() 原样透传（供网关直通上游 SSE 字节流）。
 */
class SseWriter(private val out: HttpBodyWriter) {

    suspend fun event(data: String, event: String? = null, id: String? = null) {
        val sb = StringBuilder()
        if (id != null) sb.append("id: ").append(id).append('\n')
        if (event != null) sb.append("event: ").append(event).append('\n')
        for (line in data.split('\n')) sb.append("data: ").append(line).append('\n')
        sb.append('\n')
        out.writeChunk(sb.toString())
    }

    /** SSE 注释行，用作 keepalive。 */
    suspend fun comment(text: String) = out.writeChunk(": $text\n\n")

    /** 原样透传已格式化的 SSE 文本块。 */
    suspend fun raw(text: String) = out.writeChunk(text)
}

/**
 * 以 SSE 形式流式响应。设置标准 SSE 头后进入流式写出。
 */
suspend fun HttpResponse.sse(block: suspend SseWriter.() -> Unit) {
    contentType = "text/event-stream; charset=utf-8"
    header("Cache-Control", "no-cache")
    header("X-Accel-Buffering", "no")
    stream { SseWriter(this).block() }
}
