package neton.ai.adapter.openaicompatible

import neton.http.client.create
import neton.http.client.createWithEngine

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import neton.ai.AiContent
import neton.ai.AiError
import neton.ai.AiException
import neton.ai.AiFinishReason
import neton.ai.AiMessage
import neton.ai.AiRole
import neton.ai.AiStreamEvent
import neton.ai.AiUsage
import neton.ai.ToolChoice
import neton.ai.isFallbackEligible
import neton.ai.provider.ProviderCallRequest
import neton.http.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OpenAiCompatibleStreamTest {

    private fun httpClient(engine: MockEngine): HttpClient =
        HttpClient.createWithEngine(factoryOf(engine))

    private fun factoryOf(engine: MockEngine) = object : HttpClientEngineFactory<MockEngineConfig> {
        override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = engine
    }

    private fun makeRequest(text: String = "hello") = ProviderCallRequest(
        messages = listOf(AiMessage(AiRole.User, listOf(AiContent.Text(text)))),
        tools = emptyList(),
        toolChoice = ToolChoice.Auto,
        temperature = null,
        maxTokens = null,
        topP = null,
        stopSequences = emptyList(),
        metadata = emptyMap(),
    )

    /** Test 1: plain text delta stream */
    @Test
    fun textDeltaStream() = runTest {
        val payload = buildString {
            append("data: {\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"Hello\"},\"finish_reason\":null}]}\n\n")
            append("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\" world\"},\"finish_reason\":\"stop\"}]}\n\n")
            append("data: [DONE]\n\n")
        }
        val engine = MockEngine { _ ->
            respond(
                content = ByteReadChannel(payload),
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", "text/event-stream"),
            )
        }
        val client = httpClient(engine)
        val provider = OpenAiCompatibleProvider("openai", client, "https://api.openai.com/v1", "sk-test")
        val model = provider.streamingTextModel("gpt-4o-mini")

        val events = model.stream(makeRequest()).toList()
        client.close()

        // Expect: TextDelta("Hello"), TextDelta(" world"), Completed
        val textDeltas = events.filterIsInstance<AiStreamEvent.TextDelta>()
        assertEquals(2, textDeltas.size, "Expected 2 TextDelta events")
        assertEquals("Hello", textDeltas[0].text)
        assertEquals(" world", textDeltas[1].text)

        val completed = events.filterIsInstance<AiStreamEvent.Completed>()
        assertEquals(1, completed.size, "Expected 1 Completed event")
        assertEquals("Hello world", completed[0].text)
        assertEquals(AiFinishReason.Stop, completed[0].finishReason)
    }

    /** Test 2: tool call stream with id+name in first delta, arguments in subsequent chunks */
    @Test
    fun toolCallStream() = runTest {
        val payload = buildString {
            // First delta: id and name
            append("data: {\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"tool_calls\":[{\"index\":0,\"id\":\"call_abc\",\"type\":\"function\",\"function\":{\"name\":\"get_weather\",\"arguments\":\"\"}}]},\"finish_reason\":null}]}\n\n")
            // Second delta: arguments fragment 1
            append("data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"{\\\"city\\\"\"}}]},\"finish_reason\":null}]}\n\n")
            // Third delta: arguments fragment 2
            append("data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\": \\\"London\\\"\"}}]},\"finish_reason\":null}]}\n\n")
            // Fourth delta: arguments fragment 3 + finish_reason
            append("data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"}\"}}]},\"finish_reason\":\"tool_calls\"}]}\n\n")
            append("data: [DONE]\n\n")
        }
        val engine = MockEngine { _ ->
            respond(
                content = ByteReadChannel(payload),
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", "text/event-stream"),
            )
        }
        val client = httpClient(engine)
        val provider = OpenAiCompatibleProvider("openai", client, "https://api.openai.com/v1", "sk-test")
        val model = provider.streamingTextModel("gpt-4o-mini")

        val events = model.stream(makeRequest()).toList()
        client.close()

        // ToolCallStarted
        val started = events.filterIsInstance<AiStreamEvent.ToolCallStarted>()
        assertEquals(1, started.size, "Expected 1 ToolCallStarted")
        assertEquals("call_abc", started[0].id)
        assertEquals("get_weather", started[0].name)

        // ToolCallArgumentsDelta (2 non-empty fragments: "{\"city\"" and ": \"London\"" and "}")
        val argDeltas = events.filterIsInstance<AiStreamEvent.ToolCallArgumentsDelta>()
        assertTrue(argDeltas.isNotEmpty(), "Expected ToolCallArgumentsDelta events")
        assertTrue(argDeltas.all { it.id == "call_abc" }, "All deltas should have id=call_abc")
        val fullArgs = argDeltas.joinToString("") { it.argumentsFragment }
        assertEquals("{\"city\": \"London\"}", fullArgs)

        // ToolCallReady
        val ready = events.filterIsInstance<AiStreamEvent.ToolCallReady>()
        assertEquals(1, ready.size, "Expected 1 ToolCallReady")
        assertEquals("call_abc", ready[0].call.id)
        assertEquals("get_weather", ready[0].call.name)
        // argumentsJson is the concatenation of all fragments: {"city" + : "London" + }
        assertEquals("{\"city\": \"London\"}", ready[0].call.argumentsJson)

        // Completed with ToolCalls finish reason
        val completed = events.filterIsInstance<AiStreamEvent.Completed>()
        assertEquals(1, completed.size, "Expected 1 Completed")
        assertEquals(AiFinishReason.ToolCalls, completed[0].finishReason)
        assertEquals(1, completed[0].message.toolCalls.size)
    }

    /** Test 3: usage field in last chunk */
    @Test
    fun usageInLastChunk() = runTest {
        val payload = buildString {
            append("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hi\"},\"finish_reason\":null}]}\n\n")
            append("data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":3,\"total_tokens\":13}}\n\n")
            append("data: [DONE]\n\n")
        }
        val engine = MockEngine { _ ->
            respond(
                content = ByteReadChannel(payload),
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", "text/event-stream"),
            )
        }
        val client = httpClient(engine)
        val provider = OpenAiCompatibleProvider("openai", client, "https://api.openai.com/v1", "sk-test")
        val model = provider.streamingTextModel("gpt-4o-mini")

        val events = model.stream(makeRequest()).toList()
        client.close()

        val completed = events.filterIsInstance<AiStreamEvent.Completed>()
        assertEquals(1, completed.size, "Expected 1 Completed event")
        val usage = completed[0].usage
        assertEquals(AiUsage(inputTokens = 10, outputTokens = 3, totalTokens = 13), usage)
        assertEquals(AiFinishReason.Stop, completed[0].finishReason)
        assertEquals("Hi", completed[0].text)
    }

    /** Test 4: invalid JSON chunk emits Failed and stream ends */
    @Test
    fun invalidJsonChunkEmitsFailedAndEnds() = runTest {
        val payload = buildString {
            append("data: not-valid-json\n\n")
            append("data: {\"choices\":[{\"delta\":{\"content\":\"unreachable\"},\"finish_reason\":\"stop\"}]}\n\n")
            append("data: [DONE]\n\n")
        }
        val engine = MockEngine { _ ->
            respond(
                content = ByteReadChannel(payload),
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", "text/event-stream"),
            )
        }
        val client = httpClient(engine)
        val provider = OpenAiCompatibleProvider("openai", client, "https://api.openai.com/v1", "sk-test")
        val model = provider.streamingTextModel("gpt-4o-mini")

        val events = model.stream(makeRequest()).toList()
        client.close()

        // Should emit exactly one Failed event and nothing after
        assertEquals(1, events.size, "Expected exactly 1 event (Failed)")
        assertIs<AiStreamEvent.Failed>(events[0], "Expected Failed event")
        // No Completed event should be emitted
        assertTrue(events.filterIsInstance<AiStreamEvent.Completed>().isEmpty(), "No Completed event should be emitted after failure")
    }

    @Test
    fun invalidToolArgumentsEmitFailedAndNoCompleted() = runTest {
        val payload = buildString {
            append("data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_bad\",\"type\":\"function\",\"function\":{\"name\":\"bad\",\"arguments\":\"\"}}]},\"finish_reason\":null}]}\n\n")
            append("data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"{bad-json\"}}]},\"finish_reason\":\"tool_calls\"}]}\n\n")
            append("data: [DONE]\n\n")
        }
        val engine = MockEngine { _ ->
            respond(
                content = ByteReadChannel(payload),
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", "text/event-stream"),
            )
        }
        val client = httpClient(engine)
        val provider = OpenAiCompatibleProvider("openai", client, "https://api.openai.com/v1", "sk-test")
        val model = provider.streamingTextModel("gpt-4o-mini")

        val events = model.stream(makeRequest()).toList()
        client.close()

        assertEquals(1, events.filterIsInstance<AiStreamEvent.Failed>().size)
        assertTrue(events.filterIsInstance<AiStreamEvent.ToolCallReady>().isEmpty())
        assertTrue(events.filterIsInstance<AiStreamEvent.Completed>().isEmpty())
    }

    /** Regression: streaming 429 must surface as RateLimited (was: silent empty Completed). */
    @Test
    fun stream429ThrowsRateLimitedWithProviderMessage() = runTest {
        val engine = MockEngine { _ ->
            respond(
                content = """{"error":{"message":"Rate limit exceeded, retry later","type":"rate_limit_error"}}""",
                status = HttpStatusCode.TooManyRequests,
                headers = headersOf("Content-Type", "application/json"),
            )
        }
        val client = httpClient(engine)
        val provider = OpenAiCompatibleProvider("openai", client, "https://api.openai.com/v1", "sk-test")
        val model = provider.streamingTextModel("gpt-4o-mini")

        val ex = assertFailsWith<AiException> { model.stream(makeRequest()).toList() }
        client.close()

        val err = ex.error
        assertIs<AiError.RateLimited>(err, "Expected RateLimited, got ${err::class.simpleName}")
        assertTrue("Rate limit exceeded" in err.message, "Provider error message should be parsed from body, was: ${err.message}")
    }

    /** Regression: streaming 500 must surface as ServerError and be fallback-eligible. */
    @Test
    fun stream500ThrowsServerErrorFallbackEligible() = runTest {
        val engine = MockEngine { _ ->
            respond(
                content = """{"error":{"message":"internal error","type":"server_error"}}""",
                status = HttpStatusCode.InternalServerError,
                headers = headersOf("Content-Type", "application/json"),
            )
        }
        val client = httpClient(engine)
        val provider = OpenAiCompatibleProvider("openai", client, "https://api.openai.com/v1", "sk-test")
        val model = provider.streamingTextModel("gpt-4o-mini")

        val ex = assertFailsWith<AiException> { model.stream(makeRequest()).toList() }
        client.close()

        val err = ex.error
        assertIs<AiError.ServerError>(err, "Expected ServerError, got ${err::class.simpleName}")
        assertEquals(500, err.statusCode)
        assertTrue(err.isFallbackEligible(), "5xx in stream must be fallback-eligible so the router can try the next candidate")
    }
}
