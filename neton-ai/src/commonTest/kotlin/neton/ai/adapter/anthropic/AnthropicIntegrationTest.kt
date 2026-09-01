// neton-ai/src/commonTest/kotlin/neton/ai/adapter/anthropic/AnthropicIntegrationTest.kt
package neton.ai.adapter.anthropic

import neton.http.client.create
import neton.http.client.createWithEngine

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
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
import neton.http.client.NetonHttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AnthropicIntegrationTest {

    private fun httpClient(engine: MockEngine): NetonHttpClient =
        NetonHttpClient.createWithEngine(factoryOf(engine))

    private fun factoryOf(engine: MockEngine) = object : HttpClientEngineFactory<MockEngineConfig> {
        override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = engine
    }

    @Test fun nonStreamChatMapsRequestAndResponseEndToEnd() = runTest {
        var capturedUrl: String? = null
        var capturedApiKey: String? = null
        var capturedVersion: String? = null
        var capturedAuthBearer: String? = null
        var capturedBody: String? = null

        val engine = MockEngine { req ->
            capturedUrl = req.url.toString()
            capturedApiKey = req.headers["x-api-key"]
            capturedVersion = req.headers["anthropic-version"]
            capturedAuthBearer = req.headers["Authorization"]
            capturedBody = req.body.toByteArray().decodeToString()
            respond(
                content = """{"id":"msg_1","model":"claude-3-5-sonnet-20241022","role":"assistant","content":[{"type":"text","text":"Hello!"}],"stop_reason":"end_turn","usage":{"input_tokens":10,"output_tokens":3}}""",
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", "application/json"),
            )
        }

        val client = httpClient(engine)
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
        val engine = MockEngine { _ ->
            respond(
                content = """{"role":"assistant","content":[{"type":"tool_use","id":"tu_1","name":"get_weather","input":{"city":"Tokyo"}}],"stop_reason":"tool_use"}""",
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", "application/json"),
            )
        }
        val client = httpClient(engine)
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
        val engine = MockEngine { _ ->
            respond(
                content = """{"type":"error","error":{"type":"authentication_error","message":"invalid api key"}}""",
                status = HttpStatusCode.Unauthorized,
            )
        }
        val client = httpClient(engine)
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
