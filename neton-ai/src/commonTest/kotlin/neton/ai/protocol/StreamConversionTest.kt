package neton.ai.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import neton.ai.protocol.convert.ProtocolConverter
import neton.ai.protocol.convert.StreamConverter
import neton.ai.protocol.ir.WireFormat
import neton.ai.protocol.transport.SseEvent
import neton.ai.protocol.transport.SseFramer
import neton.ai.protocol.usage.Usage
import neton.ai.protocol.usage.UsageCompleteness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreamConversionTest {

    /** 按很小的块喂入，验证跨 chunk 的分帧与状态。 */
    private fun run(conv: StreamConverter, upstream: String, chunk: Int = 7): List<SseEvent> {
        val out = StringBuilder()
        var i = 0
        while (i < upstream.length) {
            out.append(conv.feed(upstream.substring(i, minOf(upstream.length, i + chunk))))
            i += chunk
        }
        out.append(conv.finish())
        return SseFramer().feed(out.toString())
    }

    private fun SseEvent.json(): JsonObject = obj(data)

    private fun sse(vararg events: Pair<String, String>): String =
        events.joinToString("") { (e, d) -> "event: $e\ndata: $d\n\n" }

    private val anthropicStream = sse(
        "message_start" to """{"type":"message_start","message":{"id":"msg_1","model":"claude","usage":{"input_tokens":10,"output_tokens":1,"cache_read_input_tokens":4}}}""",
        "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}""",
        "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"hmm"}}""",
        "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"SIG"}}""",
        "content_block_stop" to """{"type":"content_block_stop","index":0}""",
        "content_block_start" to """{"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}""",
        "content_block_delta" to """{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"你好"}}""",
        "content_block_stop" to """{"type":"content_block_stop","index":1}""",
        "content_block_start" to """{"type":"content_block_start","index":2,"content_block":{"type":"tool_use","id":"toolu_1","name":"get","input":{}}}""",
        "content_block_delta" to """{"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"{\"a\":"}}""",
        "content_block_delta" to """{"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"1}"}}""",
        "content_block_stop" to """{"type":"content_block_stop","index":2}""",
        "message_delta" to """{"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":20}}""",
        "message_stop" to """{"type":"message_stop"}""",
    )

    @Test
    fun anthropicToOpenAiChat() {
        val conv = ProtocolConverter.streamConverter(WireFormat.ANTHROPIC, WireFormat.OPENAI_CHAT)
        val events = run(conv, anthropicStream)
        assertEquals("[DONE]", events.last().data)
        val chunks = events.dropLast(1).map { it.json() }
        val deltas = chunks.map { it["choices"]!!.jsonArray[0].jsonObject["delta"]!!.jsonObject }
        assertEquals("assistant", deltas[0]["role"]!!.jsonPrimitive.content)
        assertEquals("hmm", deltas.mapNotNull { it["reasoning_content"]?.jsonPrimitive?.content }.joinToString(""))
        assertEquals("你好", deltas.mapNotNull { it["content"]?.jsonPrimitive?.content }.joinToString(""))
        val args = deltas.flatMap { d -> d["tool_calls"]?.jsonArray?.map { it.jsonObject } ?: emptyList() }
        assertEquals("toolu_1", args[0]["id"]!!.jsonPrimitive.content)
        assertEquals("""{"a":1}""", args.joinToString("") { it["function"]!!.jsonObject["arguments"]!!.jsonPrimitive.content })
        val last = chunks.last()
        assertEquals("tool_calls", last["choices"]!!.jsonArray[0].jsonObject["finish_reason"]!!.jsonPrimitive.content)
        assertEquals(20, last["usage"]!!.jsonObject["completion_tokens"]!!.jsonPrimitive.content.toInt())
        // 用量事实：message_start 的输入与 message_delta 的输出合并
        assertTrue(conv.completed)
        assertEquals(14L, conv.usage.inputTokens)
        assertEquals(20L, conv.usage.outputTokens)
        assertEquals(UsageCompleteness.COMPLETE, conv.usage.completeness)
    }

    private val openAiStream = listOf(
        """{"id":"c1","object":"chat.completion.chunk","created":1,"model":"deepseek","choices":[{"index":0,"delta":{"role":"assistant","content":""},"finish_reason":null}]}""",
        """{"id":"c1","object":"chat.completion.chunk","created":1,"model":"deepseek","choices":[{"index":0,"delta":{"reasoning_content":"why"},"finish_reason":null}]}""",
        """{"id":"c1","object":"chat.completion.chunk","created":1,"model":"deepseek","choices":[{"index":0,"delta":{"content":"ok"},"finish_reason":null}]}""",
        """{"id":"c1","object":"chat.completion.chunk","created":1,"model":"deepseek","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_a","type":"function","function":{"name":"a","arguments":""}}]},"finish_reason":null}]}""",
        """{"id":"c1","object":"chat.completion.chunk","created":1,"model":"deepseek","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{}"}}]},"finish_reason":null}]}""",
        """{"id":"c1","object":"chat.completion.chunk","created":1,"model":"deepseek","choices":[{"index":0,"delta":{"tool_calls":[{"index":1,"id":"call_b","type":"function","function":{"name":"b","arguments":"{\"x\":2}"}}]},"finish_reason":null}]}""",
        """{"id":"c1","object":"chat.completion.chunk","created":1,"model":"deepseek","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}""",
        """{"id":"c1","object":"chat.completion.chunk","created":1,"model":"deepseek","choices":[],"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15,"prompt_tokens_details":{"cached_tokens":4}}}""",
        "[DONE]",
    ).joinToString("") { "data: $it\n\n" }

    @Test
    fun openAiChatToAnthropic() {
        val conv = ProtocolConverter.streamConverter(WireFormat.OPENAI_CHAT, WireFormat.ANTHROPIC)
        val events = run(conv, openAiStream, chunk = 5)
        val names = events.map { it.event }
        assertEquals("message_start", names.first())
        assertEquals(listOf("message_delta", "message_stop"), names.takeLast(2))
        val starts = events.filter { it.event == "content_block_start" }.map { it.json()["content_block"]!!.jsonObject["type"]!!.jsonPrimitive.content }
        assertEquals(listOf("thinking", "text", "tool_use", "tool_use"), starts)
        // thinking 收尾补一个空 signature_delta
        val sigDeltas = events.filter { it.event == "content_block_delta" && it.json()["delta"]!!.jsonObject["type"]!!.jsonPrimitive.content == "signature_delta" }
        assertEquals(1, sigDeltas.size)
        // 先关后开：每个 start 前一个块都已经 stop
        var open = -1
        for (e in events) when (e.event) {
            "content_block_start" -> { assertEquals(-1, open); open = e.json()["index"]!!.jsonPrimitive.content.toInt() }
            "content_block_stop" -> { assertEquals(open, e.json()["index"]!!.jsonPrimitive.content.toInt()); open = -1 }
        }
        val md = events.first { it.event == "message_delta" }.json()
        assertEquals("tool_use", md["delta"]!!.jsonObject["stop_reason"]!!.jsonPrimitive.content)
        // 用量在 finish_reason 之后才到，也要进 message_delta；Anthropic 口径 input 不含缓存读
        assertEquals(6, md["usage"]!!.jsonObject["input_tokens"]!!.jsonPrimitive.content.toInt())
        assertEquals(4, md["usage"]!!.jsonObject["cache_read_input_tokens"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun geminiToOpenAiCarriesSignature() {
        val gemini = listOf(
            """{"candidates":[{"content":{"role":"model","parts":[{"text":"Let"}]}}],"responseId":"r1","modelVersion":"gemini-3-pro"}""",
            """{"candidates":[{"content":{"role":"model","parts":[{"text":" me"}]}}]}""",
            """{"candidates":[{"content":{"role":"model","parts":[{"functionCall":{"name":"get","args":{"a":1}},"thoughtSignature":"REAL"}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":5,"thoughtsTokenCount":2}}""",
        ).joinToString("") { "data: $it\r\n\r\n" }
        val conv = ProtocolConverter.streamConverter(WireFormat.GEMINI, WireFormat.OPENAI_CHAT)
        val chunks = run(conv, gemini, chunk = 3).dropLast(1).map { it.json() }
        val deltas = chunks.map { it["choices"]!!.jsonArray[0].jsonObject["delta"]!!.jsonObject }
        assertEquals("Let me", deltas.mapNotNull { it["content"]?.jsonPrimitive?.content }.joinToString(""))
        val call = deltas.flatMap { it["tool_calls"]?.jsonArray ?: emptyList() }.single().jsonObject
        assertEquals("REAL", call["extra_content"]!!.jsonObject["google"]!!.jsonObject["thought_signature"]!!.jsonPrimitive.content)
        assertEquals("""{"a":1}""", call["function"]!!.jsonObject["arguments"]!!.jsonPrimitive.content)
        assertEquals("tool_calls", chunks.last()["choices"]!!.jsonArray[0].jsonObject["finish_reason"]!!.jsonPrimitive.content)
        assertEquals(7L, conv.usage.outputTokens)
        assertTrue(conv.completed)
    }

    @Test
    fun openAiChatToResponses() {
        val conv = ProtocolConverter.streamConverter(WireFormat.OPENAI_CHAT, WireFormat.OPENAI_RESPONSES)
        val events = run(conv, openAiStream)
        val types = events.map { it.json()["type"]!!.jsonPrimitive.content }
        assertEquals(listOf("response.created", "response.in_progress"), types.take(2))
        assertEquals("response.completed", types.last())
        // sequence_number 连续
        assertEquals(events.indices.toList(), events.map { it.json()["sequence_number"]!!.jsonPrimitive.content.toInt() })
        val done = events.last().json()["response"]!!.jsonObject
        val output = done["output"]!!.jsonArray.map { it.jsonObject["type"]!!.jsonPrimitive.content }
        assertEquals(listOf("reasoning", "message", "function_call", "function_call"), output)
        assertEquals(10, done["usage"]!!.jsonObject["input_tokens"]!!.jsonPrimitive.content.toInt())
        assertTrue("response.function_call_arguments.delta" in types)
        assertTrue("response.reasoning_summary_text.delta" in types)
    }

    @Test
    fun responsesToAnthropic() {
        val responses = sse(
            "response.created" to """{"type":"response.created","response":{"id":"resp_1","model":"gpt-5","created_at":1}}""",
            "response.output_item.added" to """{"type":"response.output_item.added","output_index":0,"item":{"id":"rs_1","type":"reasoning","summary":[]}}""",
            "response.reasoning_summary_text.delta" to """{"type":"response.reasoning_summary_text.delta","item_id":"rs_1","output_index":0,"summary_index":0,"delta":"plan"}""",
            "response.output_item.done" to """{"type":"response.output_item.done","output_index":0,"item":{"id":"rs_1","type":"reasoning","summary":[],"encrypted_content":"ENC"}}""",
            "response.output_item.added" to """{"type":"response.output_item.added","output_index":1,"item":{"id":"fc_1","type":"function_call","call_id":"call_1","name":"get","arguments":""}}""",
            "response.function_call_arguments.delta" to """{"type":"response.function_call_arguments.delta","item_id":"fc_1","output_index":1,"delta":"{}"}""",
            "response.output_item.done" to """{"type":"response.output_item.done","output_index":1,"item":{"id":"fc_1","type":"function_call","call_id":"call_1","name":"get","arguments":"{}"}}""",
            "response.completed" to """{"type":"response.completed","response":{"id":"resp_1","status":"completed","usage":{"input_tokens":10,"output_tokens":5,"output_tokens_details":{"reasoning_tokens":3}}}}""",
        )
        val conv = ProtocolConverter.streamConverter(WireFormat.OPENAI_RESPONSES, WireFormat.ANTHROPIC)
        val events = run(conv, responses)
        val starts = events.filter { it.event == "content_block_start" }.map { it.json()["content_block"]!!.jsonObject }
        assertEquals(listOf("thinking", "tool_use"), starts.map { it["type"]!!.jsonPrimitive.content })
        assertEquals("call_1", starts[1]["id"]!!.jsonPrimitive.content)
        assertEquals("tool_use", events.first { it.event == "message_delta" }.json()["delta"]!!.jsonObject["stop_reason"]!!.jsonPrimitive.content)
        // 加密推理不得塞进 Anthropic 的 signature
        assertFalse(events.any { "ENC" in it.data })
    }

    @Test
    fun truncatedStreamIsPartialAndNotFinalized() {
        val truncated = openAiStream.substringBefore("""{"index":0,"delta":{},"finish_reason":"tool_calls"}""").substringBeforeLast("data: ")
        val conv = ProtocolConverter.streamConverter(WireFormat.OPENAI_CHAT, WireFormat.ANTHROPIC)
        val events = run(conv, truncated)
        assertFalse(conv.completed)
        assertTrue(events.none { it.event == "message_stop" }, "must not fabricate a normal end")
        // 没有任何用量：UNKNOWN，绝不是 0
        assertEquals(UsageCompleteness.UNKNOWN, conv.usage.completeness)
        assertNull(conv.usage.inputTokens)
    }

    @Test
    fun cancelledAfterUsageIsPartial() {
        val anth = anthropicStream.substringBefore("event: message_delta")
        val conv = ProtocolConverter.streamConverter(WireFormat.ANTHROPIC, WireFormat.OPENAI_CHAT)
        val events = run(conv, anth)
        assertTrue(events.none { it.data == "[DONE]" })
        assertEquals(UsageCompleteness.PARTIAL, conv.usage.completeness)
        assertEquals(14L, conv.usage.inputTokens)
    }

    @Test
    fun identityStreamPassesThroughAndStillMeters() {
        val conv = ProtocolConverter.streamConverter(WireFormat.OPENAI_CHAT, WireFormat.OPENAI_CHAT)
        val out = StringBuilder()
        openAiStream.chunked(11).forEach { out.append(conv.feed(it)) }
        out.append(conv.finish())
        assertEquals(openAiStream, out.toString())
        assertEquals(10L, conv.usage.inputTokens)
        assertTrue(conv.completed)
    }

    @Test
    fun usageMergeRules() {
        val a = Usage(inputTokens = 10, outputTokens = 0, cacheReadTokens = 3, completeness = UsageCompleteness.PARTIAL)
        val b = Usage(inputTokens = null, outputTokens = 7, cacheReadTokens = 0)
        val m = a.mergedWith(b)
        assertEquals(10L, m.inputTokens)        // null 不覆盖已知值
        assertEquals(7L, m.outputTokens)        // 后到的已知值覆盖
        assertEquals(0L, m.cacheReadTokens)     // 已知的 0 是真实的 0
        assertEquals(UsageCompleteness.COMPLETE, m.completeness)
        assertNull(Usage().cacheWriteTokens)
    }

    @Test
    fun mistralThinkingPartsBecomeReasoning() {
        val mistral = listOf(
            """{"id":"m","choices":[{"index":0,"delta":{"content":[{"type":"thinking","thinking":[{"type":"text","text":"ponder"}]}]}}]}""",
            """{"id":"m","choices":[{"index":0,"delta":{"content":[{"type":"text","text":"Hi"}]}}]}""",
            """{"id":"m","choices":[{"index":0,"delta":{"content":" there"},"finish_reason":"stop"}]}""",
            "[DONE]",
        ).joinToString("") { "data: $it\n\n" }
        val conv = ProtocolConverter.streamConverter(WireFormat.OPENAI_CHAT, WireFormat.ANTHROPIC)
        val events = run(conv, mistral)
        val starts = events.filter { it.event == "content_block_start" }.map { it.json()["content_block"]!!.jsonObject["type"]!!.jsonPrimitive.content }
        assertEquals(listOf("thinking", "text"), starts)
        val text = events.filter { it.event == "content_block_delta" }.mapNotNull { it.json()["delta"]!!.jsonObject["text"]?.jsonPrimitive?.content }
        assertEquals("Hi there", text.joinToString(""))
    }
}
