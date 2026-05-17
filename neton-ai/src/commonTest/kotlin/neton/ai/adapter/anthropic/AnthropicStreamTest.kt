package neton.ai.adapter.anthropic

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
import neton.ai.AiFinishReason
import neton.ai.AiMessage
import neton.ai.AiRole
import neton.ai.AiStreamEvent
import neton.ai.AiUsage
import neton.ai.ToolChoice
import neton.ai.provider.ProviderCallRequest
import neton.http.client.NetonHttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AnthropicStreamTest {

    private fun httpClient(engine: MockEngine): NetonHttpClient =
        NetonHttpClient.createWithEngine(factoryOf(engine))

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

    private fun ssePayload(vararg events: Pair<String, String>): String = buildString {
        for ((eventType, data) in events) {
            append("event: $eventType\n")
            append("data: $data\n\n")
        }
    }

    /**
     * Test 1 (gate 15 partial + basic): Plain text delta stream.
     * message_start -> content_block_start -> content_block_delta -> content_block_stop
     * -> message_delta -> message_stop
     * Expects: TextDelta("Hello") + Completed(text="Hello", finishReason=Stop, usage=AiUsage(10,5,null))
     */
    @Test
    fun textDeltaStream() = runTest {
        val payload = ssePayload(
            "message_start" to """{"message":{"id":"msg_1","model":"claude-3-5-sonnet","usage":{"input_tokens":10,"output_tokens":0}}}""",
            "content_block_start" to """{"index":0,"content_block":{"type":"text","text":""}}""",
            "content_block_delta" to """{"index":0,"delta":{"type":"text_delta","text":"Hello"}}""",
            "content_block_stop" to """{"index":0}""",
            "message_delta" to """{"delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":5}}""",
            "message_stop" to "{}",
        )
        val engine = MockEngine { _ ->
            respond(
                content = ByteReadChannel(payload),
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", "text/event-stream"),
            )
        }
        val client = httpClient(engine)
        val provider = AnthropicProvider("anthropic", client, apiKey = "sk-ant-test")
        val model = provider.streamingTextModel("claude-3-5-sonnet-20241022")

        val events = model.stream(makeRequest()).toList()
        client.close()

        val textDeltas = events.filterIsInstance<AiStreamEvent.TextDelta>()
        assertEquals(1, textDeltas.size, "Expected 1 TextDelta event")
        assertEquals("Hello", textDeltas[0].text)

        val completed = events.filterIsInstance<AiStreamEvent.Completed>()
        assertEquals(1, completed.size, "Expected 1 Completed event")
        assertEquals("Hello", completed[0].text)
        assertEquals(AiFinishReason.Stop, completed[0].finishReason)
        assertEquals(AiUsage(inputTokens = 10, outputTokens = 5, totalTokens = null), completed[0].usage)
    }

    /**
     * Test 2 (gate 17): Two parallel tool_use blocks (index 0 and 1), input_json_delta interleaved.
     * Each index accumulates its own inputBuffer; each content_block_stop emits ToolCallReady.
     */
    @Test
    fun toolUseStreamPerIndexAccumulation() = runTest {
        val payload = ssePayload(
            "message_start" to """{"message":{"usage":{"input_tokens":20,"output_tokens":0}}}""",
            // block 0: tool_use "search"
            "content_block_start" to """{"index":0,"content_block":{"type":"tool_use","id":"tu_0","name":"search","input":{}}}""",
            // block 1: tool_use "weather"
            "content_block_start" to """{"index":1,"content_block":{"type":"tool_use","id":"tu_1","name":"weather","input":{}}}""",
            // interleaved deltas
            "content_block_delta" to """{"index":0,"delta":{"type":"input_json_delta","partial_json":"{\"q\":"}}""",
            "content_block_delta" to """{"index":1,"delta":{"type":"input_json_delta","partial_json":"{\"city\":"}}""",
            "content_block_delta" to """{"index":0,"delta":{"type":"input_json_delta","partial_json":"\"kotlin\"}"}}""",
            "content_block_delta" to """{"index":1,"delta":{"type":"input_json_delta","partial_json":"\"Tokyo\"}"}}""",
            // stops
            "content_block_stop" to """{"index":0}""",
            "content_block_stop" to """{"index":1}""",
            "message_delta" to """{"delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":30}}""",
            "message_stop" to "{}",
        )
        val engine = MockEngine { _ ->
            respond(
                content = ByteReadChannel(payload),
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", "text/event-stream"),
            )
        }
        val client = httpClient(engine)
        val provider = AnthropicProvider("anthropic", client, apiKey = "sk-ant-test")
        val model = provider.streamingTextModel("claude-3-5-sonnet-20241022")

        val events = model.stream(makeRequest()).toList()
        client.close()

        // Two ToolCallStarted
        val started = events.filterIsInstance<AiStreamEvent.ToolCallStarted>()
        assertEquals(2, started.size, "Expected 2 ToolCallStarted events")
        assertEquals("tu_0", started[0].id)
        assertEquals("search", started[0].name)
        assertEquals("tu_1", started[1].id)
        assertEquals("weather", started[1].name)

        // Two ToolCallReady with correctly accumulated JSON per index
        val ready = events.filterIsInstance<AiStreamEvent.ToolCallReady>()
        assertEquals(2, ready.size, "Expected 2 ToolCallReady events")

        val readyById = ready.associateBy { it.call.id }
        assertEquals("""{"q":"kotlin"}""", readyById["tu_0"]?.call?.argumentsJson)
        assertEquals("""{"city":"Tokyo"}""", readyById["tu_1"]?.call?.argumentsJson)

        // Completed with ToolCalls finish reason and both tool calls
        val completed = events.filterIsInstance<AiStreamEvent.Completed>()
        assertEquals(1, completed.size)
        assertEquals(AiFinishReason.ToolCalls, completed[0].finishReason)
        assertEquals(2, completed[0].message.toolCalls.size)
    }

    /**
     * Test 3 (gate 18): Two message_delta events with usage.output_tokens 10 and then 25.
     * Final Completed.usage.outputTokens must be 25 (cumulative, not additive).
     */
    @Test
    fun messageDeltaUsageIsCumulative() = runTest {
        val payload = ssePayload(
            "message_start" to """{"message":{"usage":{"input_tokens":5,"output_tokens":0}}}""",
            "content_block_start" to """{"index":0,"content_block":{"type":"text","text":""}}""",
            "content_block_delta" to """{"index":0,"delta":{"type":"text_delta","text":"hi"}}""",
            "content_block_stop" to """{"index":0}""",
            // First message_delta: output_tokens=10
            "message_delta" to """{"delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":10}}""",
            // Second message_delta: output_tokens=25 — this should REPLACE, not add
            "message_delta" to """{"delta":{},"usage":{"output_tokens":25}}""",
            "message_stop" to "{}",
        )
        val engine = MockEngine { _ ->
            respond(
                content = ByteReadChannel(payload),
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", "text/event-stream"),
            )
        }
        val client = httpClient(engine)
        val provider = AnthropicProvider("anthropic", client, apiKey = "sk-ant-test")
        val model = provider.streamingTextModel("claude-3-5-sonnet-20241022")

        val events = model.stream(makeRequest()).toList()
        client.close()

        val completed = events.filterIsInstance<AiStreamEvent.Completed>()
        assertEquals(1, completed.size)
        // Must be 25 (last cumulative value), NOT 35 (10+25)
        assertEquals(25, completed[0].usage?.outputTokens, "output_tokens must use last cumulative value (25), not sum (35)")
        assertEquals(5, completed[0].usage?.inputTokens, "input_tokens preserved from message_start")
    }

    /**
     * Test 4 (gate 15): ping events interspersed throughout the stream must be ignored.
     * Result must be the same as if pings were absent.
     */
    @Test
    fun pingEventsIgnored() = runTest {
        val payload = ssePayload(
            "ping" to "{}",
            "message_start" to """{"message":{"usage":{"input_tokens":8,"output_tokens":0}}}""",
            "ping" to "{}",
            "content_block_start" to """{"index":0,"content_block":{"type":"text","text":""}}""",
            "ping" to "{}",
            "content_block_delta" to """{"index":0,"delta":{"type":"text_delta","text":"pong"}}""",
            "ping" to "{}",
            "content_block_stop" to """{"index":0}""",
            "message_delta" to """{"delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":2}}""",
            "ping" to "{}",
            "message_stop" to "{}",
        )
        val engine = MockEngine { _ ->
            respond(
                content = ByteReadChannel(payload),
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", "text/event-stream"),
            )
        }
        val client = httpClient(engine)
        val provider = AnthropicProvider("anthropic", client, apiKey = "sk-ant-test")
        val model = provider.streamingTextModel("claude-3-5-sonnet-20241022")

        val events = model.stream(makeRequest()).toList()
        client.close()

        // No Failed events — pings must be silently ignored
        assertTrue(events.filterIsInstance<AiStreamEvent.Failed>().isEmpty(), "ping events must not cause failures")

        val textDeltas = events.filterIsInstance<AiStreamEvent.TextDelta>()
        assertEquals(1, textDeltas.size)
        assertEquals("pong", textDeltas[0].text)

        val completed = events.filterIsInstance<AiStreamEvent.Completed>()
        assertEquals(1, completed.size)
        assertEquals("pong", completed[0].text)
    }

    /**
     * Test 5 (gate 14): Unknown event types must be silently ignored — no Failed emitted.
     * Spec §6 gate 14: forward-compatible unknown event types must not fail the stream.
     */
    @Test
    fun unknownEventTypeIgnored() = runTest {
        val payload = ssePayload(
            "message_start" to """{"message":{"usage":{"input_tokens":3,"output_tokens":0}}}""",
            "future_event" to """{"some_field":"some_value"}""",
            "content_block_start" to """{"index":0,"content_block":{"type":"text","text":""}}""",
            "another_unknown_event" to """{}""",
            "content_block_delta" to """{"index":0,"delta":{"type":"text_delta","text":"ok"}}""",
            "content_block_stop" to """{"index":0}""",
            "message_delta" to """{"delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":1}}""",
            "message_stop" to "{}",
        )
        val engine = MockEngine { _ ->
            respond(
                content = ByteReadChannel(payload),
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", "text/event-stream"),
            )
        }
        val client = httpClient(engine)
        val provider = AnthropicProvider("anthropic", client, apiKey = "sk-ant-test")
        val model = provider.streamingTextModel("claude-3-5-sonnet-20241022")

        val events = model.stream(makeRequest()).toList()
        client.close()

        // No Failed events — unknown event types must be ignored
        assertTrue(
            events.filterIsInstance<AiStreamEvent.Failed>().isEmpty(),
            "Unknown event types must not produce Failed events (spec §6 gate 14)",
        )

        val completed = events.filterIsInstance<AiStreamEvent.Completed>()
        assertEquals(1, completed.size, "Stream must complete successfully despite unknown event types")
        assertEquals("ok", completed[0].text)
    }

    /**
     * Test 6 (gate 16): error event must emit Failed with the appropriate AiError.
     * Spec §6 gate 16: error event → emit Failed, stream terminates.
     */
    @Test
    fun errorEventEmitsFailed() = runTest {
        val payload = ssePayload(
            "message_start" to """{"message":{"usage":{"input_tokens":1,"output_tokens":0}}}""",
            "error" to """{"type":"error","error":{"type":"overloaded_error","message":"server too busy"}}""",
        )
        val engine = MockEngine { _ ->
            respond(
                content = ByteReadChannel(payload),
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", "text/event-stream"),
            )
        }
        val client = httpClient(engine)
        val provider = AnthropicProvider("anthropic", client, apiKey = "sk-ant-test")
        val model = provider.streamingTextModel("claude-3-5-sonnet-20241022")

        val events = model.stream(makeRequest()).toList()
        client.close()

        // Must emit exactly one Failed
        assertEquals(1, events.size, "Expected exactly 1 event (Failed) after error event")
        val failed = assertIs<AiStreamEvent.Failed>(events[0], "Expected Failed event")
        val err = assertIs<AiError.ServerError>(failed.error, "overloaded_error must map to AiError.ServerError(503,...)")
        assertEquals(503, err.statusCode)
        assertTrue(err.message.contains("server too busy"), "Error message must be propagated")

        // Must NOT emit Completed
        assertTrue(events.filterIsInstance<AiStreamEvent.Completed>().isEmpty(), "No Completed after error event")
    }
}
