package neton.http.client.sse

import neton.core.http.HttpHeaders

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import neton.http.client.NetonHttpStreamChunk
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
        assertEquals(listOf(NetonSseEvent(data = "hello"), NetonSseEvent(data = "world")), events)
    }

    @Test
    fun handlesCrossChunkLineFragmentation() = runTest {
        // Chunk boundaries don't align with line boundaries: "data: hel" + "lo\n\ndata: wor" + "ld\n\n"
        val chunks = flowOf(
            NetonHttpStreamChunk.Bytes("data: hel".encodeToByteArray()),
            NetonHttpStreamChunk.Bytes("lo\n\ndata: wor".encodeToByteArray()),
            NetonHttpStreamChunk.Bytes("ld\n\n".encodeToByteArray()),
            NetonHttpStreamChunk.End(HttpHeaders.EMPTY),
        )
        val events = chunks.parseSseEvents().toList()
        assertEquals(listOf(NetonSseEvent(data = "hello"), NetonSseEvent(data = "world")), events)
    }

    @Test
    fun flushesPendingEventAtStreamEnd() = runTest {
        // No trailing blank line; finish() must flush.
        val chunks = flowOf(
            NetonHttpStreamChunk.Bytes("data: pending".encodeToByteArray()),
            NetonHttpStreamChunk.End(HttpHeaders.EMPTY),
        )
        val events = chunks.parseSseEvents().toList()
        assertEquals(listOf(NetonSseEvent(data = "pending")), events)
    }

    @Test
    fun handlesCrlfLineEndings() = runTest {
        val chunks = flowOf(
            NetonHttpStreamChunk.Bytes("data: ok\r\n\r\n".encodeToByteArray()),
            NetonHttpStreamChunk.End(HttpHeaders.EMPTY),
        )
        val events = chunks.parseSseEvents().toList()
        assertEquals(listOf(NetonSseEvent(data = "ok")), events)
    }

    @Test
    fun preservesEventTypeAcrossChunks() = runTest {
        val chunks = flowOf(
            NetonHttpStreamChunk.Bytes("event: messa".encodeToByteArray()),
            NetonHttpStreamChunk.Bytes("ge_start\ndata: {}\n\n".encodeToByteArray()),
            NetonHttpStreamChunk.End(HttpHeaders.EMPTY),
        )
        val events = chunks.parseSseEvents().toList()
        assertEquals(listOf(NetonSseEvent(event = "message_start", data = "{}")), events)
    }

    @Test
    fun textChunkFlowAlsoSupported() = runTest {
        val chunks = flowOf(
            NetonHttpStreamChunk.Text("data: a"),
            NetonHttpStreamChunk.Text("\n\ndata: b\n\n"),
            NetonHttpStreamChunk.End(HttpHeaders.EMPTY),
        )
        val events = chunks.parseSseEvents().toList()
        assertEquals(listOf(NetonSseEvent(data = "a"), NetonSseEvent(data = "b")), events)
    }
}
