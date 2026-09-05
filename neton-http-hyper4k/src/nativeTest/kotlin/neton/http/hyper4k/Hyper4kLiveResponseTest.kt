package neton.http.hyper4k

import hyper4k.Hyper4kResponseChannel
import kotlinx.coroutines.runBlocking
import neton.core.http.HttpStatus
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Hyper4kLiveResponseTest {

    @Test
    fun flushesEachChunkInsteadOfBufferingTheWholeBody() = runBlocking {
        val channel = RecordingChannel()
        val response = Hyper4kLiveResponse(channel, corsHeaders = emptyMap())
        response.contentType = "text/event-stream"

        response.stream {
            writeChunk("data: 1\n\n")
            // Chunk one must reach the engine before chunk two is produced.
            // A buffering implementation is still empty here.
            assertEquals(listOf("data: 1\n\n"), channel.chunks)
            writeChunk("data: 2\n\n")
        }

        assertEquals(listOf("data: 1\n\n", "data: 2\n\n"), channel.chunks)
        assertEquals(200, channel.status)
        assertEquals(listOf("text/event-stream"), channel.headers["Content-Type"])
        assertTrue(channel.isFinished)
        assertTrue(response.isCommitted)
        assertEquals(18L, response.bytesOut)
    }

    @Test
    fun dropsContentLengthAndInjectsCorsBeforeCommitting() = runBlocking {
        val channel = RecordingChannel()
        val response = Hyper4kLiveResponse(
            channel,
            corsHeaders = mapOf("Access-Control-Allow-Origin" to listOf("https://example.com")),
        )
        // The engine frames the length per protocol, so a hand-written Content-Length
        // can only contradict it.
        response.header("Content-Length", "999")
        response.header("X-Trace", "abc")

        response.stream { writeChunk("chunk") }

        assertFalse(channel.headers.keys.any { it.equals("Content-Length", ignoreCase = true) })
        assertEquals(listOf("abc"), channel.headers["X-Trace"])
        assertEquals(listOf("https://example.com"), channel.headers["Access-Control-Allow-Origin"])
    }

    @Test
    fun stopsWritingOnceTheClientIsGone() = runBlocking {
        val channel = RecordingChannel(acceptWrites = 1)
        val response = Hyper4kLiveResponse(channel, corsHeaders = emptyMap())

        response.stream {
            writeChunk("first")
            writeChunk("second")
            writeChunk("third")
        }

        // A closed tab is a normal path: stop producing data, close as usual, no throw.
        assertContentEquals(listOf("first"), channel.chunks)
        assertTrue(response.clientGone)
        assertTrue(channel.isFinished)
    }

    @Test
    fun rejectsASecondCommit() = runBlocking {
        val channel = RecordingChannel()
        val response = Hyper4kLiveResponse(channel, corsHeaders = emptyMap())

        response.write("body".encodeToByteArray())

        assertTrue(response.isCommitted)
        assertFailsWith<neton.core.http.HttpException> {
            response.stream { writeChunk("again") }
        }
        Unit
    }

    @Test
    fun redirectCommitsWithoutABody() = runBlocking {
        val channel = RecordingChannel()
        val response = Hyper4kLiveResponse(channel, corsHeaders = emptyMap())

        response.redirect("https://example.com/next", HttpStatus.FOUND)

        // A redirect has no body, so there is nothing to stream: it goes back as a
        // complete response and the channel is never opened.
        assertTrue(response.isCommitted)
        assertFalse(response.isStreaming)
        assertNull(channel.status)
        assertFalse(channel.isFinished)

        val complete = response.completeResponse()
        assertEquals(HttpStatus.FOUND.code, complete.status)
        assertEquals(listOf("https://example.com/next"), complete.headers["Location"])
        assertEquals(0, complete.body.size)
    }

    /**
     * The contract this class exists to keep: a complete body never touches the
     * channel. A channel write crosses to hyper4k's blocking write pool, so
     * sending short responses through it costs a thread hop per request and caps
     * throughput at that pool's width.
     */
    @Test
    fun aCompleteBodyNeverOpensTheChannel() = runBlocking {
        val channel = RecordingChannel()
        val response = Hyper4kLiveResponse(
            channel,
            corsHeaders = mapOf("Access-Control-Allow-Origin" to listOf("https://example.com")),
        )
        response.header("X-Trace", "abc")

        response.write("hello".encodeToByteArray())

        assertNull(channel.status)
        assertTrue(channel.chunks.isEmpty())
        assertFalse(channel.isFinished)
        assertFalse(response.isStreaming)
        assertEquals(5L, response.bytesOut)

        val complete = response.completeResponse()
        assertEquals("hello", complete.body.decodeToString())
        assertEquals(listOf("abc"), complete.headers["X-Trace"])
        // CORS still has to be on the complete answer, same as on a streamed one.
        assertEquals(listOf("https://example.com"), complete.headers["Access-Control-Allow-Origin"])
    }

    /** Fake channel that records calls. Writes past [acceptWrites] act as a gone client. */
    private class RecordingChannel(private val acceptWrites: Int = Int.MAX_VALUE) : Hyper4kResponseChannel {
        var status: Int? = null
        var headers: Map<String, List<String>> = emptyMap()
        val chunks = mutableListOf<String>()
        var isFinished = false
        private var begun = false

        override val isStreaming: Boolean get() = begun && !isFinished
        override var bytesWritten: Long = 0L
            private set

        override suspend fun begin(status: Int, headers: Map<String, List<String>>) {
            this.status = status
            this.headers = headers
            begun = true
        }

        override suspend fun write(chunk: ByteArray): Boolean {
            if (chunks.size >= acceptWrites) return false
            chunks.add(chunk.decodeToString())
            bytesWritten += chunk.size
            return true
        }

        override suspend fun finish() {
            isFinished = true
        }
    }
}
