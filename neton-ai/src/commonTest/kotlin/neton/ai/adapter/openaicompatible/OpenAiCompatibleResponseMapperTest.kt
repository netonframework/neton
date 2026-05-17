// neton-ai/src/commonTest/kotlin/neton/ai/adapter/openaicompatible/OpenAiCompatibleResponseMapperTest.kt
package neton.ai.adapter.openaicompatible

import kotlinx.serialization.json.Json
import neton.ai.AiError
import neton.ai.AiException
import neton.ai.AiFinishReason
import neton.ai.AiRole
import neton.ai.AiUsage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OpenAiCompatibleResponseMapperTest {

    private val mapper = OpenAiCompatibleResponseMapper()
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun simpleTextResponseMapped() {
        val body = """
            {"id":"x","model":"gpt-4o-mini","choices":[{"index":0,"message":{"role":"assistant","content":"hello"},"finish_reason":"stop"}],"usage":{"prompt_tokens":5,"completion_tokens":2,"total_tokens":7}}
        """.trimIndent()
        val resp = mapper.fromWireBody(body)
        assertEquals(AiRole.Assistant, resp.message.role)
        assertEquals("hello", resp.text)
        assertEquals(AiFinishReason.Stop, resp.finishReason)
        assertEquals(AiUsage(5, 2, 7), resp.usage)
        assertTrue(resp.toolCalls.isEmpty())
    }

    @Test fun toolCallsMappedToAiToolCalls() {
        val body = """
            {"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"c1","type":"function","function":{"name":"get_balance","arguments":"{\"userId\":7}"}}]},"finish_reason":"tool_calls"}]}
        """.trimIndent()
        val resp = mapper.fromWireBody(body)
        assertEquals(AiFinishReason.ToolCalls, resp.finishReason)
        assertEquals(1, resp.toolCalls.size)
        assertEquals("c1", resp.toolCalls[0].id)
        assertEquals("get_balance", resp.toolCalls[0].name)
        assertEquals("""{"userId":7}""", resp.toolCalls[0].argumentsJson)
    }

    @Test fun finishReasonStopMapsToStop() = assertFinish("stop", AiFinishReason.Stop)
    @Test fun finishReasonLengthMapsToLength() = assertFinish("length", AiFinishReason.Length)
    @Test fun finishReasonToolCallsMapsToToolCalls() = assertFinish("tool_calls", AiFinishReason.ToolCalls)
    @Test fun finishReasonFunctionCallLegacyMapsToToolCalls() = assertFinish("function_call", AiFinishReason.ToolCalls)
    @Test fun finishReasonContentFilterMapsToContentFilter() = assertFinish("content_filter", AiFinishReason.ContentFilter)
    @Test fun finishReasonOtherStringMapsToOther() = assertFinish("weird", AiFinishReason.Other)
    @Test fun finishReasonNullMapsToOther() = assertFinish(null, AiFinishReason.Other)

    private fun assertFinish(wire: String?, expected: AiFinishReason) {
        val finishField = wire?.let { "\"finish_reason\":\"$it\"" } ?: "\"finish_reason\":null"
        val body = """{"choices":[{"message":{"role":"assistant","content":"x"},$finishField}]}"""
        assertEquals(expected, mapper.fromWireBody(body).finishReason)
    }

    @Test fun usageAbsentYieldsNullUsage() {
        val body = """{"choices":[{"message":{"role":"assistant","content":"x"},"finish_reason":"stop"}]}"""
        assertEquals(null, mapper.fromWireBody(body).usage)
    }

    // ---- HTTP error mapping ----

    @Test fun status401MapsToUnauthorized() {
        val ex = assertFailsWith<AiException> { mapper.errorFromStatus(401, """{"error":{"message":"bad key"}}""") }
        assertTrue(ex.error is AiError.Unauthorized)
    }

    @Test fun status403MapsToForbidden() {
        val ex = assertFailsWith<AiException> { mapper.errorFromStatus(403, """{"error":{"message":"x"}}""") }
        assertTrue(ex.error is AiError.Forbidden)
    }

    @Test fun status429MapsToRateLimited() {
        val ex = assertFailsWith<AiException> { mapper.errorFromStatus(429, """{"error":{"message":"slow down"}}""") }
        assertTrue(ex.error is AiError.RateLimited)
    }

    @Test fun status500MapsToServerError() {
        val ex = assertFailsWith<AiException> { mapper.errorFromStatus(500, "internal") }
        assertTrue(ex.error is AiError.ServerError)
        assertEquals(500, (ex.error as AiError.ServerError).statusCode)
    }

    @Test fun status400ContextLengthExceededMaps() {
        val body = """{"error":{"message":"context length exceeded","type":"invalid_request_error","code":"context_length_exceeded"}}"""
        val ex = assertFailsWith<AiException> { mapper.errorFromStatus(400, body) }
        assertTrue(ex.error is AiError.ContextLengthExceeded)
    }

    @Test fun status404ModelNotFoundMaps() {
        val body = """{"error":{"message":"model not found","code":"model_not_found"}}"""
        val ex = assertFailsWith<AiException> { mapper.errorFromStatus(404, body) }
        assertTrue(ex.error is AiError.ModelNotFound)
    }

    @Test fun status400OtherMapsToInvalidRequest() {
        val ex = assertFailsWith<AiException> { mapper.errorFromStatus(400, """{"error":{"message":"bad"}}""") }
        assertTrue(ex.error is AiError.InvalidRequest)
    }
}
