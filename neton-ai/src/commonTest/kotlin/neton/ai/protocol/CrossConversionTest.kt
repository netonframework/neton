package neton.ai.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import neton.ai.protocol.codec.GeminiCodec
import neton.ai.protocol.codec.GeminiEncodeOptions
import neton.ai.protocol.convert.ProtocolConverter
import neton.ai.protocol.diag.ConversionRejectedException
import neton.ai.protocol.diag.DiagnosticCodes
import neton.ai.protocol.diag.LossPolicy
import neton.ai.protocol.ir.WireFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CrossConversionTest {

    private val claudeCodeTurn = """
        {"model":"claude-sonnet-4-5","max_tokens":4096,
         "system":[{"type":"text","text":"sys","cache_control":{"type":"ephemeral"}}],
         "messages":[
           {"role":"user","content":"hi"},
           {"role":"assistant","content":[
             {"type":"thinking","thinking":"hmm","signature":"sigA"},
             {"type":"text","text":"let me check"},
             {"type":"tool_use","id":"toolu_1","name":"get","input":{"a":1}}]},
           {"role":"user","content":[
             {"type":"text","text":"also this"},
             {"type":"tool_result","tool_use_id":"toolu_1","content":[{"type":"text","text":"42"},{"type":"image","source":{"type":"base64","media_type":"image/png","data":"AAAA"}}]}]}
         ],
         "tools":[{"name":"get","input_schema":{"type":"object"}},{"type":"web_search_20250305","name":"web_search"}],
         "stream":true}
    """

    @Test
    fun anthropicToOpenAiChatRequest() {
        val r = ProtocolConverter.convertRequest(obj(claudeCodeTurn), WireFormat.ANTHROPIC, WireFormat.OPENAI_CHAT)
        val msgs = r.value["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("system", "user", "assistant", "tool", "user"), msgs.map { it["role"]!!.jsonPrimitive.content })
        assertEquals("let me check", msgs[2]["content"]!!.jsonPrimitive.content)
        assertEquals("toolu_1", msgs[2]["tool_calls"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content)
        // 工具结果紧跟 assistant；结果里的图片与同条消息的文本随后作为 user 消息
        assertEquals("42", msgs[3]["content"]!!.jsonPrimitive.content)
        val trailing = msgs[4]["content"]!!.jsonArray.map { it.jsonObject["type"]!!.jsonPrimitive.content }
        assertEquals(listOf("image_url", "text"), trailing)
        val codes = r.diagnostics.map { it.code }.toSet()
        assertTrue(DiagnosticCodes.HOSTED_TOOL_OMITTED in codes)
        assertTrue(DiagnosticCodes.REASONING_DROPPED in codes)
        assertTrue(DiagnosticCodes.CACHE_CONTROL_DROPPED in codes)
    }

    @Test
    fun lossPolicies() {
        val body = obj(claudeCodeTurn)
        // safe：托管工具被丢弃是 error 级，拒绝
        val e = assertFailsWith<ConversionRejectedException> {
            ProtocolConverter.convertRequest(body, WireFormat.ANTHROPIC, WireFormat.OPENAI_CHAT, LossPolicy.Safe())
        }
        assertTrue(e.violations.all { it.code == DiagnosticCodes.HOSTED_TOOL_OMITTED })
        // safe + 已授权降级：放行，诊断照常记录
        val ok = ProtocolConverter.convertRequest(body, WireFormat.ANTHROPIC, WireFormat.OPENAI_CHAT,
            LossPolicy.Safe(setOf(DiagnosticCodes.HOSTED_TOOL_OMITTED)))
        assertTrue(ok.diagnostics.any { it.code == DiagnosticCodes.HOSTED_TOOL_OMITTED })
        // strict 不受白名单影响
        assertFailsWith<ConversionRejectedException> {
            ProtocolConverter.convertRequest(body, WireFormat.ANTHROPIC, WireFormat.OPENAI_CHAT, LossPolicy.Strict)
        }
    }

    @Test
    fun openAiChatToAnthropicRequest() {
        val r = ProtocolConverter.convertRequest(obj("""
            {"model":"x","max_tokens":4096,"reasoning_effort":"low","tool_choice":"required",
             "messages":[
               {"role":"system","content":"sys"},
               {"role":"user","content":"hi"},
               {"role":"assistant","content":null,"tool_calls":[
                 {"id":"c1","type":"function","function":{"name":"a","arguments":"{\"x\":1}"}},
                 {"id":"c2","type":"function","function":{"name":"b","arguments":""}}]},
               {"role":"tool","tool_call_id":"c1","content":"r1"},
               {"role":"tool","tool_call_id":"c2","content":"r2"},
               {"role":"user","content":"next"}
             ],
             "tools":[{"type":"function","function":{"name":"a","parameters":{"type":"object"}}},{"type":"function","function":{"name":"b"}}]}
        """), WireFormat.OPENAI_CHAT, WireFormat.ANTHROPIC)
        val v = r.value
        assertEquals("sys", v["system"]!!.jsonPrimitive.content)
        assertEquals(JsonPrimitive("any"), v["tool_choice"]!!.jsonObject["type"])
        assertEquals(1024, v["thinking"]!!.jsonObject["budget_tokens"]!!.jsonPrimitive.content.toInt())
        val msgs = v["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("user", "assistant", "user"), msgs.map { it["role"]!!.jsonPrimitive.content })
        // 两条 tool 消息与随后的 user 文本合并成一条 user，tool_result 在前
        val last = msgs[2]["content"]!!.jsonArray.map { it.jsonObject["type"]!!.jsonPrimitive.content }
        assertEquals(listOf("tool_result", "tool_result", "text"), last)
        // 空参数写成空对象
        assertEquals(JsonObject(emptyMap()), msgs[1]["content"]!!.jsonArray[1].jsonObject["input"])
    }

    @Test
    fun anthropicResponseToOpenAiKeepsReasoningTextOrderAndUsage() {
        val r = ProtocolConverter.convertResponse(obj("""
            {"id":"msg_1","type":"message","role":"assistant","model":"claude",
             "content":[{"type":"thinking","thinking":"t","signature":"s"},{"type":"text","text":"hi"},{"type":"tool_use","id":"toolu_1","name":"get","input":{"a":1}}],
             "stop_reason":"tool_use","usage":{"input_tokens":10,"output_tokens":5,"cache_read_input_tokens":3,"cache_creation_input_tokens":2}}
        """), WireFormat.ANTHROPIC, WireFormat.OPENAI_CHAT)
        val choice = r.value["choices"]!!.jsonArray[0].jsonObject
        val msg = choice["message"]!!.jsonObject
        assertEquals("t", msg["reasoning_content"]!!.jsonPrimitive.content)
        assertEquals("hi", msg["content"]!!.jsonPrimitive.content)
        assertEquals("""{"a":1}""", msg["tool_calls"]!!.jsonArray[0].jsonObject["function"]!!.jsonObject["arguments"]!!.jsonPrimitive.content)
        assertEquals("tool_calls", choice["finish_reason"]!!.jsonPrimitive.content)
        val usage = r.value["usage"]!!.jsonObject
        assertEquals(15, usage["prompt_tokens"]!!.jsonPrimitive.content.toInt())
        assertEquals(3, usage["prompt_tokens_details"]!!.jsonObject["cached_tokens"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun openAiResponseToAnthropicShowsReasoningWithEmptySignature() {
        val r = ProtocolConverter.convertResponse(obj("""
            {"id":"c","object":"chat.completion","created":1,"model":"deepseek",
             "choices":[{"index":0,"message":{"role":"assistant","content":"hi","reasoning_content":"why"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15,"prompt_tokens_details":{"cached_tokens":4}}}
        """), WireFormat.OPENAI_CHAT, WireFormat.ANTHROPIC)
        val content = r.value["content"]!!.jsonArray.map { it.jsonObject }
        assertEquals("thinking", content[0]["type"]!!.jsonPrimitive.content)
        assertEquals("", content[0]["signature"]!!.jsonPrimitive.content)
        assertEquals("end_turn", r.value["stop_reason"]!!.jsonPrimitive.content)
        // Anthropic 口径：input_tokens 不含缓存读
        val usage = r.value["usage"]!!.jsonObject
        assertEquals(6, usage["input_tokens"]!!.jsonPrimitive.content.toInt())
        assertEquals(4, usage["cache_read_input_tokens"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun geminiSignatureRoundTripsThroughOpenAiClient() {
        // 上游 Gemini 响应 → OpenAI 客户端：签名进官方 extra_content 字段
        val resp = ProtocolConverter.convertResponse(obj("""
            {"candidates":[{"content":{"role":"model","parts":[{"functionCall":{"name":"get","args":{"a":1}},"thoughtSignature":"REAL"}]},"finishReason":"STOP"}]}
        """), WireFormat.GEMINI, WireFormat.OPENAI_CHAT)
        val call = resp.value["choices"]!!.jsonArray[0].jsonObject["message"]!!.jsonObject["tool_calls"]!!.jsonArray[0].jsonObject
        assertEquals("REAL", call["extra_content"]!!.jsonObject["google"]!!.jsonObject["thought_signature"]!!.jsonPrimitive.content)
        // 客户端原样带回 → 下一轮 Gemini 请求写回 thoughtSignature
        val next = ProtocolConverter.convertRequest(obj("""
            {"model":"gemini-3-pro","messages":[
              {"role":"user","content":"hi"},
              {"role":"assistant","content":null,"tool_calls":[$call]},
              {"role":"tool","tool_call_id":"${call["id"]!!.jsonPrimitive.content}","content":"{\"r\":1}"}]}
        """), WireFormat.OPENAI_CHAT, WireFormat.GEMINI, model = "gemini-3-pro")
        val parts = next.value["contents"]!!.jsonArray[1].jsonObject["parts"]!!.jsonArray
        assertEquals("REAL", parts[0].jsonObject["thoughtSignature"]!!.jsonPrimitive.content)
        val fr = next.value["contents"]!!.jsonArray[2].jsonObject["parts"]!!.jsonArray[0].jsonObject["functionResponse"]!!.jsonObject
        assertEquals("get", fr["name"]!!.jsonPrimitive.content)
        assertTrue(next.diagnostics.none { it.code == DiagnosticCodes.GEMINI_THOUGHT_SIGNATURE_MISSING })
    }

    private val unsignedOpenAiTurn = """
        {"model":"gemini-3-pro","messages":[
          {"role":"user","content":"old"},
          {"role":"assistant","content":null,"tool_calls":[{"id":"o1","type":"function","function":{"name":"get","arguments":"{}"}}]},
          {"role":"tool","tool_call_id":"o1","content":"x"},
          {"role":"user","content":"now"},
          {"role":"assistant","content":"sure","tool_calls":[
            {"id":"c1","type":"function","function":{"name":"get","arguments":"{}"}},
            {"id":"c2","type":"function","function":{"name":"get","arguments":"{}"}}]},
          {"role":"tool","tool_call_id":"c1","content":"y"},
          {"role":"tool","tool_call_id":"c2","content":"z"}]}
    """

    @Test
    fun missingSignatureIsDiagnosedOnlyInCurrentTurnAndBypassOnlyOnFirstCall() {
        val missing = ProtocolConverter.convertRequest(obj(unsignedOpenAiTurn), WireFormat.OPENAI_CHAT, WireFormat.GEMINI, model = "gemini-3-pro")
        assertEquals(1, missing.diagnostics.count { it.code == DiagnosticCodes.GEMINI_THOUGHT_SIGNATURE_MISSING })
        // safe 策略拒绝缺签名（必要状态缺失是 error 级）
        assertFailsWith<ConversionRejectedException> {
            ProtocolConverter.convertRequest(obj(unsignedOpenAiTurn), WireFormat.OPENAI_CHAT, WireFormat.GEMINI, LossPolicy.Safe(), model = "gemini-3-pro")
        }
        // Gemini 2.5 不校验签名：不报缺失
        val old = ProtocolConverter.convertRequest(obj(unsignedOpenAiTurn), WireFormat.OPENAI_CHAT, WireFormat.GEMINI, model = "gemini-2.5-pro")
        assertTrue(old.diagnostics.none { it.code == DiagnosticCodes.GEMINI_THOUGHT_SIGNATURE_MISSING })

        val bypassed = ProtocolConverter.convertRequest(obj(unsignedOpenAiTurn), WireFormat.OPENAI_CHAT, WireFormat.GEMINI,
            LossPolicy.Safe(setOf(DiagnosticCodes.GEMINI_THOUGHT_SIGNATURE_BYPASSED)), model = "gemini-3-pro",
            gemini = GeminiEncodeOptions(signatureBypass = true))
        val contents = bypassed.value["contents"]!!.jsonArray.map { it.jsonObject }
        // 旧轮不写；当前轮只写第一个 functionCall，不写文本
        assertFalse(contents[1]["parts"]!!.jsonArray[0].jsonObject.containsKey("thoughtSignature"))
        // tool 结果与随后的用户文本相邻同角色，合并为一个 content：当前轮的 model 在下标 3
        val current = contents[3]["parts"]!!.jsonArray.map { it.jsonObject }
        assertFalse(current[0].containsKey("thoughtSignature"))  // 文本 "sure"
        assertEquals(GeminiCodec.SIGNATURE_BYPASS_VALUE, current[1]["thoughtSignature"]!!.jsonPrimitive.content)
        assertFalse(current[2].containsKey("thoughtSignature"))
        assertEquals(1, bypassed.diagnostics.count { it.code == DiagnosticCodes.GEMINI_THOUGHT_SIGNATURE_BYPASSED })
    }

    @Test
    fun geminiSignatureNeverLeaksIntoOtherVendorReasoningFields() {
        val resp = ProtocolConverter.convertResponse(obj("""
            {"candidates":[{"content":{"role":"model","parts":[{"text":"t","thought":true,"thoughtSignature":"G"},{"functionCall":{"name":"get","args":{}},"thoughtSignature":"G2"}]},"finishReason":"STOP"}]}
        """), WireFormat.GEMINI, WireFormat.ANTHROPIC)
        val text = resp.value.toString()
        assertFalse("\"G\"" in text || "G2" in text, text)
        val responses = ProtocolConverter.convertResponse(obj("""
            {"candidates":[{"content":{"role":"model","parts":[{"functionCall":{"name":"get","args":{}},"thoughtSignature":"G2"}]},"finishReason":"STOP"}]}
        """), WireFormat.GEMINI, WireFormat.OPENAI_RESPONSES)
        assertFalse("G2" in responses.value.toString())
    }

    @Test
    fun responsesAndChatAreGoodQuality() {
        val r = ProtocolConverter.convertRequest(obj("""
            {"model":"gpt-5","instructions":"be brief","input":"hi","reasoning":{"effort":"high"},
             "text":{"format":{"type":"json_schema","name":"o","schema":{"type":"object"},"strict":true}},
             "tools":[{"type":"function","name":"get","parameters":{"type":"object"}}]}
        """), WireFormat.OPENAI_RESPONSES, WireFormat.OPENAI_CHAT)
        val v = r.value
        assertEquals("developer", v["messages"]!!.jsonArray[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("high", v["reasoning_effort"]!!.jsonPrimitive.content)
        assertEquals("json_schema", v["response_format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        // Responses 的 function 默认 strict=true，转到 Chat 时显式写出
        assertEquals(JsonPrimitive(true), v["tools"]!!.jsonArray[0].jsonObject["function"]!!.jsonObject["strict"])
        assertTrue(r.diagnostics.isEmpty(), r.diagnostics.toString())

        val back = ProtocolConverter.convertRequest(v, WireFormat.OPENAI_CHAT, WireFormat.OPENAI_RESPONSES)
        assertEquals("high", back.value["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertEquals(JsonPrimitive(true), back.value["tools"]!!.jsonArray[0].jsonObject["strict"])
    }

    @Test
    fun chatToolNotStrictStaysNotStrictOnResponses() {
        val r = ProtocolConverter.convertRequest(obj("""
            {"model":"m","messages":[{"role":"user","content":"hi"}],"tools":[{"type":"function","function":{"name":"get","parameters":{"type":"object"}}}]}
        """), WireFormat.OPENAI_CHAT, WireFormat.OPENAI_RESPONSES)
        assertEquals(JsonPrimitive(false), r.value["tools"]!!.jsonArray[0].jsonObject["strict"])
    }

    @Test
    fun errorsRenderInClientProtocol() {
        val (status, body) = ProtocolConverter.convertError(529,
            """{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}""", WireFormat.ANTHROPIC, WireFormat.OPENAI_CHAT)
        assertEquals(529, status)
        val err = obj(body)["error"]!!.jsonObject
        assertEquals("Overloaded", err["message"]!!.jsonPrimitive.content)
        assertEquals("api_error", err["type"]!!.jsonPrimitive.content)

        val (_, gemini) = ProtocolConverter.convertError(429, """{"error":{"message":"slow down","type":"rate_limit_error"}}""",
            WireFormat.OPENAI_CHAT, WireFormat.GEMINI)
        assertEquals("RESOURCE_EXHAUSTED", obj(gemini)["error"]!!.jsonObject["status"]!!.jsonPrimitive.content)

        // 同协议原样透传
        val raw = """{"error":{"message":"x"}}"""
        assertEquals(raw, ProtocolConverter.convertError(400, raw, WireFormat.OPENAI_CHAT, WireFormat.OPENAI_CHAT).second)
    }

    @Test
    fun identityPassesBodyThroughUnchanged() {
        val body = obj("""{"model":"m","messages":[],"weird_vendor_field":{"a":[1,2]}}""")
        val r = ProtocolConverter.convertRequest(body, WireFormat.OPENAI_CHAT, WireFormat.OPENAI_CHAT)
        assertTrue(r.value === body)
        assertTrue(r.diagnostics.isEmpty())
    }

    @Test
    fun structuredOutputMapsToAnthropicOutputConfig() {
        val r = ProtocolConverter.convertRequest(obj("""
            {"model":"m","messages":[{"role":"user","content":"hi"}],
             "response_format":{"type":"json_schema","json_schema":{"name":"o","schema":{"type":"object","additionalProperties":false},"strict":true}}}
        """), WireFormat.OPENAI_CHAT, WireFormat.ANTHROPIC)
        val format = r.value["output_config"]!!.jsonObject["format"]!!.jsonObject
        assertEquals("json_schema", format["type"]!!.jsonPrimitive.content)
        assertEquals("object", format["schema"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertTrue(r.diagnostics.isEmpty(), r.diagnostics.toString())
        // 反向：Anthropic 的 output_config.format（以及已废弃的 output_format）→ OpenAI json_schema
        for (field in listOf(""""output_config":{"format":{"type":"json_schema","schema":{"type":"object"}},"effort":"high"}""",
                             """"output_format":{"type":"json_schema","schema":{"type":"object"}}""")) {
            val back = ProtocolConverter.convertRequest(obj("""{"model":"m","max_tokens":10,"messages":[{"role":"user","content":"hi"}],$field}"""),
                WireFormat.ANTHROPIC, WireFormat.OPENAI_CHAT)
            assertEquals("json_schema", back.value["response_format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun unstructuredUpstreamErrorsKeepTheUpstreamText() {
        val (_, body) = ProtocolConverter.convertError(403, """{"error": "forbidden"}""", WireFormat.OPENAI_CHAT, WireFormat.ANTHROPIC)
        assertEquals("forbidden", obj(body)["error"]!!.jsonObject["message"]!!.jsonPrimitive.content)
        assertEquals("permission_error", obj(body)["error"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        val (_, plain) = ProtocolConverter.convertError(502, "Bad Gateway from nginx", WireFormat.OPENAI_CHAT, WireFormat.GEMINI)
        assertEquals("Bad Gateway from nginx", obj(plain)["error"]!!.jsonObject["message"]!!.jsonPrimitive.content)
    }

    @Test
    fun reasoningBudgetsScaleWithTheOutputLimit() {
        fun anthropic(effort: String, max: Int) = ProtocolConverter.convertRequest(obj("""
            {"model":"m","max_tokens":$max,"reasoning_effort":"$effort","messages":[{"role":"user","content":"hi"}]}
        """), WireFormat.OPENAI_CHAT, WireFormat.ANTHROPIC).value
        val high = anthropic("high", 32000)
        assertEquals(19200, high["thinking"]!!.jsonObject["budget_tokens"]!!.jsonPrimitive.content.toInt())
        assertEquals(32000, high["max_tokens"]!!.jsonPrimitive.content.toInt())
        // 下限 1024；装不下时把 max_tokens 抬到预算 + 1024
        val low = anthropic("low", 300)
        assertEquals(1024, low["thinking"]!!.jsonObject["budget_tokens"]!!.jsonPrimitive.content.toInt())
        assertEquals(2048, low["max_tokens"]!!.jsonPrimitive.content.toInt())
    }
}
