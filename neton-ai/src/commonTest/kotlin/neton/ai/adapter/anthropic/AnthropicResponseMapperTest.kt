// neton-ai/src/commonTest/kotlin/neton/ai/adapter/anthropic/AnthropicResponseMapperTest.kt
package neton.ai.adapter.anthropic

import neton.ai.AiError
import neton.ai.AiException
import neton.ai.AiFinishReason
import neton.ai.AiRole
import neton.ai.AiUsage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnthropicResponseMapperTest {

    private val mapper = AnthropicResponseMapper()

    // --- Scene 1: Single text block → text + assistant message ---
    @Test fun singleTextBlockMappedToText() {
        val body = """
            {"id":"msg_1","model":"claude-3-5-sonnet","role":"assistant",
             "content":[{"type":"text","text":"hello world"}],
             "stop_reason":"end_turn",
             "usage":{"input_tokens":10,"output_tokens":3}}
        """.trimIndent()
        val resp = mapper.fromWireBody(body)
        assertEquals(AiRole.Assistant, resp.message.role)
        assertEquals("hello world", resp.text)
        assertEquals(1, resp.message.content.size)
        assertTrue(resp.toolCalls.isEmpty())
    }

    // --- Scene 2: Multiple text blocks → concatenated with \n ---
    @Test fun multipleTextBlocksConcatenatedWithNewline() {
        val body = """
            {"role":"assistant",
             "content":[{"type":"text","text":"first"},{"type":"text","text":"second"}],
             "stop_reason":"end_turn"}
        """.trimIndent()
        val resp = mapper.fromWireBody(body)
        assertEquals("first\nsecond", resp.text)
    }

    // --- Scene 3: Single tool_use block → toolCalls populated, text empty, finishReason=ToolCalls ---
    @Test fun singleToolUseBlockMapsToToolCalls() {
        val body = """
            {"role":"assistant",
             "content":[{"type":"tool_use","id":"tu_1","name":"get_balance","input":{"userId":7}}],
             "stop_reason":"tool_use"}
        """.trimIndent()
        val resp = mapper.fromWireBody(body)
        assertEquals("", resp.text)
        assertTrue(resp.message.content.isEmpty())
        assertEquals(1, resp.toolCalls.size)
        assertEquals("tu_1", resp.toolCalls[0].id)
        assertEquals("get_balance", resp.toolCalls[0].name)
        assertEquals(AiFinishReason.ToolCalls, resp.finishReason)
    }

    // --- Scene 4: Interleaved text + tool_use ---
    @Test fun interleavedTextAndToolUseExtractsBoth() {
        val body = """
            {"role":"assistant",
             "content":[
               {"type":"text","text":"I will call the tool"},
               {"type":"tool_use","id":"tu_2","name":"search","input":{"q":"kotlin"}}
             ],
             "stop_reason":"tool_use"}
        """.trimIndent()
        val resp = mapper.fromWireBody(body)
        assertEquals("I will call the tool", resp.text)
        assertEquals(1, resp.toolCalls.size)
        assertEquals("search", resp.toolCalls[0].name)
    }

    // --- Scene 5: stop_reason="end_turn" → AiFinishReason.Stop ---
    @Test fun stopReasonEndTurnMapsToStop() {
        assertEquals(AiFinishReason.Stop, mapStopReason("end_turn"))
    }

    // --- Scene 6: stop_reason="max_tokens" → AiFinishReason.Length ---
    @Test fun stopReasonMaxTokensMapsToLength() {
        assertEquals(AiFinishReason.Length, mapStopReason("max_tokens"))
    }

    // --- Scene 7: stop_reason="tool_use" → AiFinishReason.ToolCalls ---
    @Test fun stopReasonToolUseMapsToToolCalls() {
        assertEquals(AiFinishReason.ToolCalls, mapStopReason("tool_use"))
    }

    // --- Scene 8: stop_reason="stop_sequence" → AiFinishReason.Stop ---
    @Test fun stopReasonStopSequenceMapsToStop() {
        assertEquals(AiFinishReason.Stop, mapStopReason("stop_sequence"))
    }

    // --- Scene 9: stop_reason=null/unknown → AiFinishReason.Other ---
    @Test fun stopReasonNullMapsToOther() {
        assertEquals(AiFinishReason.Other, mapStopReason(null))
    }

    @Test fun stopReasonUnknownStringMapsToOther() {
        assertEquals(AiFinishReason.Other, mapStopReason("weird_value"))
    }

    // --- Scene 10: usage.input/output_tokens → AiUsage with totalTokens=null ---
    @Test fun usageMappedWithNullTotalTokens() {
        val body = """
            {"role":"assistant",
             "content":[{"type":"text","text":"x"}],
             "stop_reason":"end_turn",
             "usage":{"input_tokens":15,"output_tokens":5}}
        """.trimIndent()
        val resp = mapper.fromWireBody(body)
        assertEquals(AiUsage(inputTokens = 15, outputTokens = 5, totalTokens = null), resp.usage)
    }

    // --- Scene 11: usage absent → null ---
    @Test fun usageAbsentYieldsNull() {
        val body = """
            {"role":"assistant",
             "content":[{"type":"text","text":"x"}],
             "stop_reason":"end_turn"}
        """.trimIndent()
        assertNull(mapper.fromWireBody(body).usage)
    }

    // --- Scene 12: Error mapping ---
    @Test fun status401MapsToUnauthorized() {
        val ex = assertFailsWith<AiException> {
            mapper.errorFromStatus(401, """{"type":"error","error":{"type":"authentication_error","message":"bad api key"}}""")
        }
        assertTrue(ex.error is AiError.Unauthorized)
        assertEquals("bad api key", ex.error.message)
    }

    @Test fun status403MapsToForbidden() {
        val ex = assertFailsWith<AiException> {
            mapper.errorFromStatus(403, """{"type":"error","error":{"type":"permission_error","message":"not allowed"}}""")
        }
        assertTrue(ex.error is AiError.Forbidden)
    }

    @Test fun status429MapsToRateLimited() {
        val ex = assertFailsWith<AiException> {
            mapper.errorFromStatus(429, """{"type":"error","error":{"type":"rate_limit_error","message":"slow down"}}""")
        }
        assertTrue(ex.error is AiError.RateLimited)
    }

    @Test fun status5xxMapsToServerError() {
        val ex = assertFailsWith<AiException> {
            mapper.errorFromStatus(529, """{"type":"error","error":{"type":"overloaded_error","message":"overloaded"}}""")
        }
        assertTrue(ex.error is AiError.ServerError)
        assertEquals(529, (ex.error as AiError.ServerError).statusCode)
    }

    @Test fun status400WithContextLengthMapsToContextLengthExceeded() {
        val ex = assertFailsWith<AiException> {
            mapper.errorFromStatus(400, """{"type":"error","error":{"type":"invalid_request_error","message":"prompt is too long: context length exceeded"}}""")
        }
        assertTrue(ex.error is AiError.ContextLengthExceeded)
    }

    @Test fun status400OtherInvalidRequestMapsToInvalidRequest() {
        val ex = assertFailsWith<AiException> {
            mapper.errorFromStatus(400, """{"type":"error","error":{"type":"invalid_request_error","message":"bad param"}}""")
        }
        assertTrue(ex.error is AiError.InvalidRequest)
    }

    // Helper to test just the stop-reason mapping via a minimal wire body
    private fun mapStopReason(stopReason: String?): AiFinishReason {
        val stopReasonField = if (stopReason != null) "\"stop_reason\":\"$stopReason\"" else "\"stop_reason\":null"
        val body = """{"role":"assistant","content":[{"type":"text","text":"x"}],$stopReasonField}"""
        return mapper.fromWireBody(body).finishReason
    }
}
