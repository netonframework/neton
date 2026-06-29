// SSE parser contract.
package neton.http.client.sse

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NetonSseParserTest {

    @Test
    fun emitsSingleEventOnBlankLine() {
        val parser = NetonSseParser()
        assertTrue(parser.accept("data: hello").isEmpty(), "data line alone does not finalize event")
        val events = parser.accept("")
        assertEquals(1, events.size)
        assertEquals(NetonSseEvent(data = "hello"), events[0])
    }

    @Test
    fun stripsLeadingSpaceOnDataField() {
        val parser = NetonSseParser()
        parser.accept("data:  with-two-leading-spaces")
        val events = parser.accept("")
        // SSE spec: strip ONE leading space if present
        assertEquals(" with-two-leading-spaces", events.single().data)
    }

    @Test
    fun parsesEventAndIdFields() {
        val parser = NetonSseParser()
        parser.accept("id: 42")
        parser.accept("event: message_start")
        parser.accept("data: {\"k\":1}")
        val events = parser.accept("")
        assertEquals(NetonSseEvent(id = "42", event = "message_start", data = "{\"k\":1}"), events.single())
    }

    @Test
    fun concatenatesMultiLineData() {
        val parser = NetonSseParser()
        parser.accept("data: line1")
        parser.accept("data: line2")
        val events = parser.accept("")
        assertEquals("line1\nline2", events.single().data)
    }

    @Test
    fun ignoresCommentLines() {
        val parser = NetonSseParser()
        parser.accept(": keep-alive comment")
        parser.accept("data: real")
        val events = parser.accept("")
        assertEquals("real", events.single().data)
    }

    @Test
    fun ignoresUnknownFields() {
        val parser = NetonSseParser()
        parser.accept("retry: 5000")  // SSE retry field, not stored in NetonSseEvent (only id/event/data)
        parser.accept("data: ok")
        val events = parser.accept("")
        assertEquals(NetonSseEvent(data = "ok"), events.single())
    }

    @Test
    fun emitsDoneSentinelAsRegularEvent() {
        // OpenAI uses "data: [DONE]" — parser treats it as a regular event with data="[DONE]".
        // Consumer decides whether [DONE] terminates the logical stream.
        val parser = NetonSseParser()
        parser.accept("data: [DONE]")
        val events = parser.accept("")
        assertEquals("[DONE]", events.single().data)
    }

    @Test
    fun finishFlushesPendingEvent() {
        val parser = NetonSseParser()
        parser.accept("data: no-trailing-newline")
        // No blank line yet; event is buffered.
        val flushed = parser.finish()
        assertEquals("no-trailing-newline", flushed.single().data)
    }

    @Test
    fun finishReturnsEmptyWhenNoPendingEvent() {
        val parser = NetonSseParser()
        parser.accept("data: ok")
        parser.accept("")  // flushed
        assertEquals(emptyList(), parser.finish())
    }

    @Test
    fun handlesMultipleEventsInSequence() {
        val parser = NetonSseParser()
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
        val parser = NetonSseParser()
        parser.accept("data")  // field=data value=""
        val events = parser.accept("")
        assertEquals("", events.single().data)
    }

    @Test
    fun blankLineBeforeAnyDataFieldEmitsNothing() {
        // Per spec: if data buffer is empty, do not dispatch an event
        val parser = NetonSseParser()
        val events = parser.accept("")
        assertTrue(events.isEmpty())
    }

    @Test
    fun eventFieldWithoutDataStillDispatchesIfDataAccumulated() {
        val parser = NetonSseParser()
        parser.accept("event: ping")
        parser.accept("data:")  // empty data, still triggers dispatch
        val events = parser.accept("")
        assertEquals(NetonSseEvent(event = "ping", data = ""), events.single())
    }
}
