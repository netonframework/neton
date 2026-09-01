// SSE parser contract.
package neton.http.client.sse

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SseParserTest {

    @Test
    fun emitsSingleEventOnBlankLine() {
        val parser = SseParser()
        assertTrue(parser.accept("data: hello").isEmpty(), "data line alone does not finalize event")
        val events = parser.accept("")
        assertEquals(1, events.size)
        assertEquals(SseEvent(data = "hello"), events[0])
    }

    @Test
    fun stripsLeadingSpaceOnDataField() {
        val parser = SseParser()
        parser.accept("data:  with-two-leading-spaces")
        val events = parser.accept("")
        // SSE spec: strip ONE leading space if present
        assertEquals(" with-two-leading-spaces", events.single().data)
    }

    @Test
    fun parsesEventAndIdFields() {
        val parser = SseParser()
        parser.accept("id: 42")
        parser.accept("event: message_start")
        parser.accept("data: {\"k\":1}")
        val events = parser.accept("")
        assertEquals(SseEvent(id = "42", event = "message_start", data = "{\"k\":1}"), events.single())
    }

    @Test
    fun concatenatesMultiLineData() {
        val parser = SseParser()
        parser.accept("data: line1")
        parser.accept("data: line2")
        val events = parser.accept("")
        assertEquals("line1\nline2", events.single().data)
    }

    @Test
    fun ignoresCommentLines() {
        val parser = SseParser()
        parser.accept(": keep-alive comment")
        parser.accept("data: real")
        val events = parser.accept("")
        assertEquals("real", events.single().data)
    }

    @Test
    fun ignoresUnknownFields() {
        val parser = SseParser()
        parser.accept("retry: 5000")  // SSE retry field, not stored in SseEvent (only id/event/data)
        parser.accept("data: ok")
        val events = parser.accept("")
        assertEquals(SseEvent(data = "ok"), events.single())
    }

    @Test
    fun emitsDoneSentinelAsRegularEvent() {
        // OpenAI uses "data: [DONE]" — parser treats it as a regular event with data="[DONE]".
        // Consumer decides whether [DONE] terminates the logical stream.
        val parser = SseParser()
        parser.accept("data: [DONE]")
        val events = parser.accept("")
        assertEquals("[DONE]", events.single().data)
    }

    @Test
    fun finishFlushesPendingEvent() {
        val parser = SseParser()
        parser.accept("data: no-trailing-newline")
        // No blank line yet; event is buffered.
        val flushed = parser.finish()
        assertEquals("no-trailing-newline", flushed.single().data)
    }

    @Test
    fun finishReturnsEmptyWhenNoPendingEvent() {
        val parser = SseParser()
        parser.accept("data: ok")
        parser.accept("")  // flushed
        assertEquals(emptyList(), parser.finish())
    }

    @Test
    fun handlesMultipleEventsInSequence() {
        val parser = SseParser()
        parser.accept("data: first")
        val first = parser.accept("")
        parser.accept("data: second")
        val second = parser.accept("")
        assertEquals("first", first.single().data)
        assertEquals("second", second.single().data)
    }

    @Test
    fun fieldWithoutColonIsTreatedAsFieldNameWithEmptyValue() {
        // Per spec: "data" without ":" → field name = "data", value = "" → empty data line
        val parser = SseParser()
        parser.accept("data")  // field=data value=""
        val events = parser.accept("")
        assertEquals("", events.single().data)
    }

    @Test
    fun blankLineBeforeAnyDataFieldEmitsNothing() {
        // Per spec: if data buffer is empty, do not dispatch an event
        val parser = SseParser()
        val events = parser.accept("")
        assertTrue(events.isEmpty())
    }

    @Test
    fun eventFieldWithoutDataStillDispatchesIfDataAccumulated() {
        val parser = SseParser()
        parser.accept("event: ping")
        parser.accept("data:")  // empty data, still triggers dispatch
        val events = parser.accept("")
        assertEquals(SseEvent(event = "ping", data = ""), events.single())
    }
}
