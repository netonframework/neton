// Outbound HTTP client cancellation contract.
package neton.http.client

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import neton.http.client.sse.parseSseEvents
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CancellationTest {

    @Test
    fun streamYieldsByteChunksAndEnd() = runTest {
        val payload = "data: hello\n\ndata: world\n\n"
        val engine = MockEngine { _ ->
            respond(
                content = ByteReadChannel(payload),
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", "text/event-stream"),
            )
        }
        val client = KtorHttpClient(engineFactory = engineFactoryFor(engine))
        val chunks = client.stream(HttpClientRequest(
            method = HttpClientMethod.Get,
            url = "https://api.example.com/stream",
        )).toList()

        // Must end with HttpClientStreamChunk.End
        assertTrue(chunks.last() is HttpClientStreamChunk.End, "Last chunk must be End, was ${chunks.last()::class.simpleName}")
        // Concatenated byte content must equal payload
        val combined = chunks.filterIsInstance<HttpClientStreamChunk.Bytes>()
            .joinToString(separator = "") { it.bytes.decodeToString() }
        assertEquals(payload, combined)
        client.close()
    }

    @Test
    fun streamParsedAsSseProducesExpectedEvents() = runTest {
        val payload = "data: a\n\nevent: ping\ndata: \n\ndata: b\n\n"
        val engine = MockEngine { _ ->
            respond(
                content = ByteReadChannel(payload),
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", "text/event-stream"),
            )
        }
        val client = KtorHttpClient(engineFactory = engineFactoryFor(engine))
        val events = client.stream(HttpClientRequest(method = HttpClientMethod.Get, url = "https://x/stream"))
            .parseSseEvents()
            .toList()
        assertEquals(3, events.size)
        assertEquals("a", events[0].data)
        assertEquals("ping", events[1].event)
        assertEquals("b", events[2].data)
        client.close()
    }

    @Test
    fun cancellingFlowEarlyDoesNotThrowFailedAndStopsReading() = runTest {
        // Long-running payload; we take only the first event then cancel.
        val payload = buildString {
            repeat(100) { append("data: event-$it\n\n") }
        }
        val engine = MockEngine { _ ->
            respond(
                content = ByteReadChannel(payload),
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", "text/event-stream"),
            )
        }
        val client = KtorHttpClient(engineFactory = engineFactoryFor(engine))
        // take(1) cancels the upstream flow after first element
        val first = client.stream(HttpClientRequest(method = HttpClientMethod.Get, url = "https://x/stream"))
            .parseSseEvents()
            .take(1)
            .toList()
        assertEquals(1, first.size)
        assertEquals("event-0", first[0].data)
        // No exception thrown; structured concurrency closed underlying response.
        client.close()
    }
}

private fun engineFactoryFor(engine: MockEngine): io.ktor.client.engine.HttpClientEngineFactory<io.ktor.client.engine.mock.MockEngineConfig> {
    return object : io.ktor.client.engine.HttpClientEngineFactory<io.ktor.client.engine.mock.MockEngineConfig> {
        override fun create(block: io.ktor.client.engine.mock.MockEngineConfig.() -> Unit): io.ktor.client.engine.HttpClientEngine = engine
    }
}
