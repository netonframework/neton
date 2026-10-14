package neton.ai.protocol

import neton.ai.protocol.codec.ProtocolCodecs
import neton.ai.protocol.diag.Diagnostics
import neton.ai.protocol.ir.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 每种协议的请求、响应解码再编码回自身无损（spec §12 第 1 步出口条件），
 * 以及 §4.4 列出的保留项在 IR 里确实存在。
 */
class RoundTripTest {

    private fun roundTripRequest(format: WireFormat, text: String): ChatRequest {
        val codec = ProtocolCodecs.of(format)
        val d = Diagnostics(format, format)
        val ir = codec.decodeRequest(obj(text), d)
        assertJsonEquivalent(obj(text), codec.encodeRequest(ir, d))
        assertTrue(d.isEmpty(), "unexpected diagnostics: ${d.items}")
        return ir
    }

    private fun roundTripResponse(format: WireFormat, text: String): ChatResponse {
        val codec = ProtocolCodecs.of(format)
        val d = Diagnostics(format, format)
        val ir = codec.decodeResponse(obj(text), d)
        assertJsonEquivalent(obj(text), codec.encodeResponse(ir, d))
        assertTrue(d.isEmpty(), "unexpected diagnostics: ${d.items}")
        return ir
    }

    @Test
    fun openAiChatRequest() {
        val ir = roundTripRequest(WireFormat.OPENAI_CHAT, """
            {"model":"gpt-4o","messages":[
              {"role":"system","content":"be brief"},
              {"role":"user","content":[{"type":"text","text":"look"},{"type":"image_url","image_url":{"url":"data:image/png;base64,AAAA","detail":"high"}}]},
              {"role":"assistant","content":null,"tool_calls":[{"id":"call_1","type":"function","function":{"name":"get","arguments":"{\"a\":1}"},"extra_content":{"google":{"thought_signature":"sig1"}}}]},
              {"role":"tool","tool_call_id":"call_1","content":"42"},
              {"role":"assistant","content":"done"}
            ],
            "tools":[{"type":"function","function":{"name":"get","description":"d","parameters":{"type":"object","properties":{}}}}],
            "tool_choice":"auto","temperature":0.5,"max_tokens":100,"stop":["x"],"stream":true,"stream_options":{"include_usage":true},
            "logit_bias":{"1":2},"reasoning_effort":"low"}
        """)
        assertEquals("be brief", ir.instructions.single().text)
        val call = ir.messages[1].content.single() as ToolCallBlock
        // Gemini 签名从官方 OpenAI 兼容字段读入 IR
        assertEquals("sig1", call.metadata.string(ProviderMetadata.GEMINI, ProviderMetadata.THOUGHT_SIGNATURE))
        assertEquals("low", ir.options.reasoning?.effort)
        assertEquals(true, ir.streamUsage)
    }

    @Test
    fun openAiChatResponse() {
        val ir = roundTripResponse(WireFormat.OPENAI_CHAT, """
            {"id":"chatcmpl-1","object":"chat.completion","created":1700000000,"model":"gpt-4o",
             "choices":[{"index":0,"message":{"role":"assistant","content":"hi","reasoning_content":"think",
               "tool_calls":[{"id":"call_1","type":"function","function":{"name":"get","arguments":"{}"}}]},"finish_reason":"tool_calls","logprobs":null}],
             "usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15,"prompt_tokens_details":{"cached_tokens":2}},
             "system_fingerprint":"fp"}
        """)
        assertEquals(listOf(ReasoningBlock::class, TextBlock::class, ToolCallBlock::class), ir.content.map { it::class })
        assertEquals(FinishReason.ToolCalls, ir.finishReason)
        assertEquals(10L, ir.usage?.inputTokens)
        assertEquals(2L, ir.usage?.cacheReadTokens)
    }

    @Test
    fun anthropicRequestKeepsBlockOrderSignaturesAndCacheControl() {
        val ir = roundTripRequest(WireFormat.ANTHROPIC, """
            {"model":"claude-sonnet-4-5","max_tokens":4096,
             "system":[{"type":"text","text":"sys","cache_control":{"type":"ephemeral","ttl":"1h"}}],
             "messages":[
               {"role":"user","content":"hi"},
               {"role":"assistant","content":[
                 {"type":"thinking","thinking":"hmm","signature":"sigA"},
                 {"type":"text","text":"let me check"},
                 {"type":"tool_use","id":"toolu_1","name":"get","input":{"a":1}},
                 {"type":"redacted_thinking","data":"xyz"}]},
               {"role":"user","content":[
                 {"type":"tool_result","tool_use_id":"toolu_1","content":[{"type":"text","text":"42"},{"type":"image","source":{"type":"base64","media_type":"image/png","data":"AAAA"}}],"is_error":true},
                 {"type":"text","text":"thanks","cache_control":{"type":"ephemeral"}}]}
             ],
             "tools":[{"name":"get","description":"d","input_schema":{"type":"object"}},{"type":"web_search_20250305","name":"web_search","max_uses":3}],
             "tool_choice":{"type":"auto","disable_parallel_tool_use":true},
             "thinking":{"type":"enabled","budget_tokens":2048},
             "metadata":{"user_id":"u1"},"stream":true,"service_tier":"auto"}
        """)
        val assistant = ir.messages[1].content
        // 块顺序原样保留（P0：Anthropic 块顺序）
        assertEquals(listOf(ReasoningBlock::class, TextBlock::class, ToolCallBlock::class, ReasoningBlock::class), assistant.map { it::class })
        assertEquals("sigA", (assistant[0] as ReasoningBlock).signature)
        assertTrue((assistant[3] as ReasoningBlock).redacted)
        val cacheControl = ir.instructions.single().metadata[ProviderMetadata.ANTHROPIC, ProviderMetadata.CACHE_CONTROL] as kotlinx.serialization.json.JsonObject
        assertEquals(kotlinx.serialization.json.JsonPrimitive("1h"), cacheControl["ttl"])
        assertTrue(ir.tools[1] is HostedTool)
        assertEquals(false, ir.options.parallelToolCalls)
        assertEquals(ReasoningMode.Enabled, ir.options.reasoning?.mode)
    }

    @Test
    fun anthropicResponse() {
        val ir = roundTripResponse(WireFormat.ANTHROPIC, """
            {"id":"msg_1","type":"message","role":"assistant","model":"claude",
             "content":[{"type":"thinking","thinking":"t","signature":"s"},{"type":"text","text":"hi"},{"type":"tool_use","id":"toolu_1","name":"get","input":{"a":1}}],
             "stop_reason":"tool_use","stop_sequence":null,
             "usage":{"input_tokens":10,"output_tokens":5,"cache_read_input_tokens":3,"cache_creation_input_tokens":2}}
        """)
        // IR 口径：input 包含缓存读写
        assertEquals(15L, ir.usage?.inputTokens)
        assertEquals(3L, ir.usage?.cacheReadTokens)
        assertEquals(2L, ir.usage?.cacheWrite5mTokens)
    }

    @Test
    fun geminiRequest() {
        val ir = roundTripRequest(WireFormat.GEMINI, """
            {"contents":[
              {"role":"user","parts":[{"text":"hi"},{"inlineData":{"mimeType":"image/png","data":"AAAA"}}]},
              {"role":"model","parts":[{"text":"thinking...","thought":true},{"functionCall":{"name":"get","args":{"a":1}},"thoughtSignature":"gsig"},{"functionCall":{"name":"get","args":{"a":2}}}]},
              {"role":"user","parts":[{"functionResponse":{"name":"get","response":{"r":1}}},{"functionResponse":{"name":"get","response":{"r":2}}}]}
             ],
             "systemInstruction":{"parts":[{"text":"sys"}]},
             "tools":[{"functionDeclarations":[{"name":"get","description":"d","parameters":{"type":"OBJECT"}}]},{"googleSearch":{}}],
             "toolConfig":{"functionCallingConfig":{"mode":"ANY"}},
             "generationConfig":{"temperature":0.5,"maxOutputTokens":100,"thinkingConfig":{"thinkingBudget":1024,"includeThoughts":true},"responseMimeType":"application/json"},
             "safetySettings":[{"category":"HARM_CATEGORY_HARASSMENT","threshold":"BLOCK_NONE"}]}
        """)
        val calls = ir.messages[1].content.filterIsInstance<ToolCallBlock>()
        val results = ir.messages[2].content.filterIsInstance<ToolResultBlock>()
        // 同名并行调用：结果按顺序回配到各自的调用
        assertEquals(calls.map { it.id }, results.map { it.callId })
        assertEquals("gsig", calls[0].metadata.string(ProviderMetadata.GEMINI, ProviderMetadata.THOUGHT_SIGNATURE))
        assertEquals(null, calls[1].metadata.string(ProviderMetadata.GEMINI, ProviderMetadata.THOUGHT_SIGNATURE))
    }

    @Test
    fun geminiResponse() {
        val ir = roundTripResponse(WireFormat.GEMINI, """
            {"candidates":[{"content":{"role":"model","parts":[{"text":"t","thought":true},{"text":"hi"},{"functionCall":{"name":"get","args":{"a":1}},"thoughtSignature":"g1"}]},
              "finishReason":"STOP","index":0,"safetyRatings":[]}],
             "usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":5,"thoughtsTokenCount":3,"totalTokenCount":18},
             "modelVersion":"gemini-3-pro","responseId":"r1"}
        """)
        // 有 functionCall 的 STOP 在 IR 里是 ToolCalls；IR 口径的 output 含思考
        assertEquals(FinishReason.ToolCalls, ir.finishReason)
        assertEquals(8L, ir.usage?.outputTokens)
        assertEquals(3L, ir.usage?.reasoningTokens)
    }

    @Test
    fun responsesRequestKeepsEncryptedReasoningAndItemIds() {
        val ir = roundTripRequest(WireFormat.OPENAI_RESPONSES, """
            {"model":"gpt-5","instructions":"be brief","input":[
              {"type":"message","role":"user","content":[{"type":"input_text","text":"hi"}]},
              {"type":"reasoning","id":"rs_1","summary":[{"type":"summary_text","text":"thought"}],"encrypted_content":"ENC"},
              {"type":"function_call","id":"fc_1","call_id":"call_1","name":"get","arguments":"{}"},
              {"type":"function_call_output","call_id":"call_1","output":"42"},
              {"type":"message","role":"assistant","id":"msg_1","content":[{"type":"output_text","text":"done","annotations":[]}]}
             ],
             "tools":[{"type":"function","name":"get","description":"d","parameters":{"type":"object"},"strict":false},{"type":"web_search"}],
             "tool_choice":"auto","reasoning":{"effort":"high","summary":"auto"},"text":{"format":{"type":"text"},"verbosity":"low"},
             "max_output_tokens":100,"stream":true,"store":false,"include":["reasoning.encrypted_content"]}
        """)
        val reasoning = ir.messages[1].content[0] as ReasoningBlock
        assertEquals("ENC", reasoning.encryptedContent)
        assertEquals("rs_1", reasoning.metadata.string(ProviderMetadata.OPENAI, ProviderMetadata.ITEM_ID))
        assertEquals(Role.Tool, ir.messages[2].role)
    }

    @Test
    fun responsesResponse() {
        val ir = roundTripResponse(WireFormat.OPENAI_RESPONSES, """
            {"id":"resp_1","object":"response","created_at":1700000000,"status":"completed","error":null,"incomplete_details":null,"model":"gpt-5",
             "output":[
               {"type":"reasoning","id":"rs_1","summary":[],"encrypted_content":"ENC"},
               {"type":"message","role":"assistant","id":"msg_1","status":"completed","content":[{"type":"output_text","text":"hi","annotations":[]}]},
               {"type":"function_call","id":"fc_1","call_id":"call_1","name":"get","arguments":"{}","status":"completed"}],
             "usage":{"input_tokens":10,"input_tokens_details":{"cached_tokens":2},"output_tokens":5,"output_tokens_details":{"reasoning_tokens":3},"total_tokens":15},
             "parallel_tool_calls":true}
        """)
        assertEquals(FinishReason.ToolCalls, ir.finishReason)
        assertEquals(3L, ir.usage?.reasoningTokens)
    }
}
