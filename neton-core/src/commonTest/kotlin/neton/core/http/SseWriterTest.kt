package neton.core.http

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class SseWriterTest {

    private class Recorder : HttpBodyWriter {
        val sb = StringBuilder()
        override suspend fun writeChunk(chunk: ByteArray) { sb.append(chunk.decodeToString()) }
    }

    @Test
    fun formatsSimpleDataEvent() = runBlocking {
        val rec = Recorder()
        SseWriter(rec).event(data = """{"x":1}""")
        assertEquals("data: {\"x\":1}\n\n", rec.sb.toString())
    }

    @Test
    fun formatsNamedEventWithIdAndMultilineData() = runBlocking {
        val rec = Recorder()
        SseWriter(rec).event(data = "line1\nline2", event = "message_delta", id = "42")
        assertEquals("id: 42\nevent: message_delta\ndata: line1\ndata: line2\n\n", rec.sb.toString())
    }

    @Test
    fun commentIsKeepaliveFormat() = runBlocking {
        val rec = Recorder()
        SseWriter(rec).comment("ping")
        assertEquals(": ping\n\n", rec.sb.toString())
    }

    @Test
    fun rawPassesBytesThroughUnchanged() = runBlocking {
        val rec = Recorder()
        // 中转直通场景：上游已格式化的 SSE 块原样转发，不得二次加工
        SseWriter(rec).raw("data: [DONE]\n\n")
        assertEquals("data: [DONE]\n\n", rec.sb.toString())
    }

    @Test
    fun sseExtensionSetsHeadersAndStreams() = runBlocking {
        val response = object : HttpResponse {
            override var status: HttpStatus = HttpStatus.OK
            override val headers: MutableHeaders = TestHeaders()
            override val isCommitted: Boolean get() = body != null
            var body: ByteArray? = null
            override fun cookie(cookie: Cookie) {}
            override suspend fun write(data: ByteArray) { body = data }
        }
        response.sse { event(data = "hi") }
        assertEquals("text/event-stream; charset=utf-8", response.headers["Content-Type"])
        assertEquals("no-cache", response.headers["Cache-Control"])
        assertEquals("data: hi\n\n", response.body!!.decodeToString())
    }
}
