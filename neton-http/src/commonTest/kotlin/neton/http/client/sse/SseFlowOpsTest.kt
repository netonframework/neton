package neton.http.client.sse

import neton.core.http.HttpHeaders

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import neton.http.client.HttpClientStreamChunk
import kotlin.test.Test
import kotlin.test.assertEquals

class SseFlowOpsTest {

    @Test
    fun parsesSimpleStringFlow() = runTest {
        val events = flowOf(
            "data: hello",
            "",
            "data: world",
            "",
        ).parseSseEvents().toList()
        assertEquals(listOf(SseEvent(data = "hello"), SseEvent(data = "world")), events)
    }

    @Test
    fun handlesCrossChunkLineFragmentation() = runTest {
        // Chunk boundaries don't align with line boundaries: "data: hel" + "lo\n\ndata: wor" + "ld\n\n"
        val chunks = flowOf(
            HttpClientStreamChunk.Bytes("data: hel".encodeToByteArray()),
            HttpClientStreamChunk.Bytes("lo\n\ndata: wor".encodeToByteArray()),
            HttpClientStreamChunk.Bytes("ld\n\n".encodeToByteArray()),
            HttpClientStreamChunk.End(HttpHeaders.EMPTY),
        )
        val events = chunks.parseSseEvents().toList()
        assertEquals(listOf(SseEvent(data = "hello"), SseEvent(data = "world")), events)
    }

    @Test
    fun flushesPendingEventAtStreamEnd() = runTest {
        // No trailing blank line; finish() must flush.
        val chunks = flowOf(
            HttpClientStreamChunk.Bytes("data: pending".encodeToByteArray()),
            HttpClientStreamChunk.End(HttpHeaders.EMPTY),
        )
        val events = chunks.parseSseEvents().toList()
        assertEquals(listOf(SseEvent(data = "pending")), events)
    }

    @Test
    fun handlesCrlfLineEndings() = runTest {
        val chunks = flowOf(
            HttpClientStreamChunk.Bytes("data: ok\r\n\r\n".encodeToByteArray()),
            HttpClientStreamChunk.End(HttpHeaders.EMPTY),
        )
        val events = chunks.parseSseEvents().toList()
        assertEquals(listOf(SseEvent(data = "ok")), events)
    }

    @Test
    fun preservesEventTypeAcrossChunks() = runTest {
        val chunks = flowOf(
            HttpClientStreamChunk.Bytes("event: messa".encodeToByteArray()),
            HttpClientStreamChunk.Bytes("ge_start\ndata: {}\n\n".encodeToByteArray()),
            HttpClientStreamChunk.End(HttpHeaders.EMPTY),
        )
        val events = chunks.parseSseEvents().toList()
        assertEquals(listOf(SseEvent(event = "message_start", data = "{}")), events)
    }

    @Test
    fun textChunkFlowAlsoSupported() = runTest {
        val chunks = flowOf(
            HttpClientStreamChunk.Text("data: a"),
            HttpClientStreamChunk.Text("\n\ndata: b\n\n"),
            HttpClientStreamChunk.End(HttpHeaders.EMPTY),
        )
        val events = chunks.parseSseEvents().toList()
        assertEquals(listOf(SseEvent(data = "a"), SseEvent(data = "b")), events)
    }
}
