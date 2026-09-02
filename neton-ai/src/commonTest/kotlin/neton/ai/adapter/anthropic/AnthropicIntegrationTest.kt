// neton-ai/src/commonTest/kotlin/neton/ai/adapter/anthropic/AnthropicIntegrationTest.kt
package neton.ai.adapter.anthropic

import neton.ai.testkit.bodyText
import neton.ai.testkit.jsonResponse
import neton.http.client.HttpClientMethod
import neton.http.testkit.ScriptedHttpClient

import kotlinx.coroutines.test.runTest
import neton.ai.AiContent
import neton.ai.AiException
import neton.ai.AiFinishReason
import neton.ai.AiMessage
import neton.ai.AiRole
import neton.ai.AiToolDefinition
import neton.ai.AiToolExecutor
import neton.ai.ToolChoice
import neton.ai.provider.ProviderCallRequest
import neton.http.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AnthropicIntegrationTest {

    @Test fun nonStreamChatMapsRequestAndResponseEndToEnd() = runTest {
        var capturedUrl: String? = null
        var capturedApiKey: String? = null
        var capturedVersion: String? = null
        var capturedAuthBearer: String? = null
        var capturedBody: String? = null

        val engine = ScriptedHttpClient().on(HttpClientMethod.Post, "") { req ->
            capturedUrl = req.url
            capturedApiKey = req.headers.get("x-api-key")
            capturedVersion = req.headers.get("anthropic-version")
            capturedAuthBearer = req.headers.get("Authorization")
            capturedBody = req.bodyText()
            jsonResponse(200, """{"id":"msg_1","model":"claude-3-5-sonnet-20241022","role":"assistant","content":[{"type":"text","text":"Hello!"}],"stop_reason":"end_turn","usage":{"input_tokens":10,"output_tokens":3}}""")}

        val client = engine
        val provider = AnthropicProvider(
            id = "anthropic", httpClient = client,
            baseUrl = "https://api.anthropic.com",
            apiKey = "sk-ant-test",
            version = "2023-06-01",
        )
        val model = provider.textModel("claude-3-5-sonnet-20241022")
        val resp = model.generate(ProviderCallRequest(
            messages = listOf(AiMessage(AiRole.User, listOf(AiContent.Text("Hi")))),
            tools = emptyList(), toolChoice = ToolChoice.Auto,
            temperature = null, maxTokens = null, topP = null,
            stopSequences = emptyList(), metadata = emptyMap(),
        ))

        assertEquals("https://api.anthropic.com/v1/messages", capturedUrl)
        assertEquals("sk-ant-test", capturedApiKey)
        assertEquals("2023-06-01", capturedVersion)
        // Must NOT use Authorization: Bearer header
        assertTrue(capturedAuthBearer == null, "Anthropic must not use Authorization: Bearer")
        assertTrue(capturedBody!!.contains("\"model\":\"claude-3-5-sonnet-20241022\""))
        assertTrue(capturedBody!!.contains("\"role\":\"user\""))
        assertEquals("Hello!", resp.text)
        assertEquals(AiFinishReason.Stop, resp.finishReason)
        assertEquals(10, resp.usage?.inputTokens)
        assertEquals(3, resp.usage?.outputTokens)
        assertEquals(null, resp.usage?.totalTokens)
        client.close()
    }

    @Test fun toolUseResponseMapsToToolCalls() = runTest {
        val engine = ScriptedHttpClient().on(HttpClientMethod.Post, "") { _ ->
            jsonResponse(200, """{"role":"assistant","content":[{"type":"tool_use","id":"tu_1","name":"get_weather","input":{"city":"Tokyo"}}],"stop_reason":"tool_use"}""")}
        val client = engine
        val provider = AnthropicProvider(
            id = "anthropic", httpClient = client,
            apiKey = "sk-ant-test",
        )
        val resp = provider.textModel("claude-3-5-haiku-20241022").generate(ProviderCallRequest(
            messages = listOf(AiMessage(AiRole.User, listOf(AiContent.Text("Weather?")))),
            tools = listOf(AiToolDefinition("get_weather", "Get weather", "{}", AiToolExecutor { "" })),
            toolChoice = ToolChoice.Auto,
            temperature = null, maxTokens = null, topP = null,
            stopSequences = emptyList(), metadata = emptyMap(),
        ))
        assertEquals(1, resp.toolCalls.size)
        assertEquals("get_weather", resp.toolCalls[0].name)
        assertEquals("tu_1", resp.toolCalls[0].id)
        assertEquals(AiFinishReason.ToolCalls, resp.finishReason)
        assertEquals("", resp.text)
        client.close()
    }

    @Test fun http401MapsToUnauthorizedAiError() = runTest {
        val engine = ScriptedHttpClient().on(HttpClientMethod.Post, "") { _ ->
            jsonResponse(401, """{"type":"error","error":{"type":"authentication_error","message":"invalid api key"}}""")}
        val client = engine
        val provider = AnthropicProvider(
            id = "anthropic", httpClient = client,
            apiKey = "bad-key",
        )
        val ex = assertFailsWith<AiException> {
            provider.textModel("claude-3-5-haiku-20241022").generate(ProviderCallRequest(
                messages = listOf(AiMessage(AiRole.User, listOf(AiContent.Text("x")))),
                tools = emptyList(), toolChoice = ToolChoice.Auto,
                temperature = null, maxTokens = null, topP = null,
                stopSequences = emptyList(), metadata = emptyMap(),
            ))
        }
        assertTrue(ex.error is neton.ai.AiError.Unauthorized)
        client.close()
    }
}
