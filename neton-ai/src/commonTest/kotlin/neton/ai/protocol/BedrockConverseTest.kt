package neton.ai.protocol

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import neton.ai.protocol.codec.BedrockConverseCodec
import neton.ai.protocol.convert.ProtocolConverter
import neton.ai.protocol.diag.Diagnostics
import neton.ai.protocol.ir.WireFormat
import neton.ai.protocol.transport.SseFramer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BedrockConverseTest {
    @Test
    fun requestRoundTrip() {
        val text = """
            {"messages":[
              {"role":"user","content":[{"text":"hi"},{"image":{"format":"png","source":{"bytes":"AAAA"}}},{"cachePoint":{"type":"default"}}]},
              {"role":"assistant","content":[{"reasoningContent":{"reasoningText":{"text":"t","signature":"S"}}},{"toolUse":{"toolUseId":"tu1","name":"get","input":{"a":1}}}]},
              {"role":"user","content":[{"toolResult":{"toolUseId":"tu1","content":[{"text":"42"}],"status":"error"}}]}
             ],
             "system":[{"text":"sys"},{"cachePoint":{"type":"default"}}],
             "inferenceConfig":{"maxTokens":100,"temperature":0.5,"stopSequences":["x"]},
             "toolConfig":{"tools":[{"toolSpec":{"name":"get","description":"d","inputSchema":{"json":{"type":"object"}}}}],"toolChoice":{"any":{}}},
             "additionalModelRequestFields":{"top_k":5}}
        """
        val d = Diagnostics(WireFormat.BEDROCK_CONVERSE, WireFormat.BEDROCK_CONVERSE)
        val ir = BedrockConverseCodec.decodeRequest(obj(text), d)
        assertJsonEquivalent(obj(text), BedrockConverseCodec.encodeRequest(ir, d))
        assertTrue(d.isEmpty(), d.items.toString())
    }

    @Test
    fun anthropicCacheControlBecomesCachePoint() {
        val r = ProtocolConverter.convertRequest(obj("""
            {"model":"m","max_tokens":10,"system":[{"type":"text","text":"long","cache_control":{"type":"ephemeral"}}],
             "messages":[{"role":"user","content":"hi"}]}
        """), WireFormat.ANTHROPIC, WireFormat.BEDROCK_CONVERSE)
        val system = r.value["system"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("text", "cachePoint"), system.map { it.keys.first() })
        assertEquals(10, r.value["inferenceConfig"]!!.jsonObject["maxTokens"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun responseToOpenAi() {
        val r = ProtocolConverter.convertResponse(obj("""
            {"output":{"message":{"role":"assistant","content":[{"text":"ok"},{"toolUse":{"toolUseId":"t1","name":"get","input":{}}}]}},
             "stopReason":"tool_use","usage":{"inputTokens":10,"outputTokens":3,"totalTokens":13,"cacheReadInputTokens":4}}
        """), WireFormat.BEDROCK_CONVERSE, WireFormat.OPENAI_CHAT)
        val choice = r.value["choices"]!!.jsonArray[0].jsonObject
        assertEquals("tool_calls", choice["finish_reason"]!!.jsonPrimitive.content)
        assertEquals(14, r.value["usage"]!!.jsonObject["prompt_tokens"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun decodedEventStreamToAnthropic() {
        // EventStreamDecoder 的输出形式：event 名来自 :event-type 头
        val upstream = listOf(
            "messageStart" to """{"role":"assistant"}""",
            "contentBlockDelta" to """{"contentBlockIndex":0,"delta":{"text":"Hel"}}""",
            "contentBlockDelta" to """{"contentBlockIndex":0,"delta":{"text":"lo"}}""",
            "contentBlockStop" to """{"contentBlockIndex":0}""",
            "contentBlockStart" to """{"contentBlockIndex":1,"start":{"toolUse":{"toolUseId":"t1","name":"get"}}}""",
            "contentBlockDelta" to """{"contentBlockIndex":1,"delta":{"toolUse":{"input":"{\"a\":1}"}}}""",
            "contentBlockStop" to """{"contentBlockIndex":1}""",
            "messageStop" to """{"stopReason":"tool_use"}""",
            "metadata" to """{"usage":{"inputTokens":5,"outputTokens":2,"totalTokens":7},"metrics":{"latencyMs":10}}""",
        ).joinToString("") { (e, d) -> "event: $e\ndata: $d\n\n" }
        val conv = ProtocolConverter.streamConverter(WireFormat.BEDROCK_CONVERSE, WireFormat.ANTHROPIC)
        val out = conv.feed(upstream) + conv.finish()
        val events = SseFramer().feed(out)
        assertTrue(conv.completed)
        val starts = events.filter { it.event == "content_block_start" }.map { obj(it.data)["content_block"]!!.jsonObject["type"]!!.jsonPrimitive.content }
        assertEquals(listOf("text", "tool_use"), starts)
        assertEquals(JsonPrimitive("tool_use"), obj(events.first { it.event == "message_delta" }.data)["delta"]!!.jsonObject["stop_reason"])
        assertEquals(5L, conv.usage.inputTokens)
    }
}
