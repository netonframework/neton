// neton-ai/src/commonTest/kotlin/neton/ai/adapter/openaicompatible/OpenAiCompatibleIntegrationTest.kt
package neton.ai.adapter.openaicompatible

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
import neton.ai.AiFinishReason
import neton.ai.AiMessage
import neton.ai.AiRole
import neton.ai.AiToolDefinition
import neton.ai.AiToolExecutor
import neton.ai.AiUsage
import neton.ai.ToolChoice
import neton.ai.provider.ProviderCallRequest
import neton.http.client.NetonHttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpenAiCompatibleIntegrationTest {

    private fun httpClient(engine: MockEngine): NetonHttpClient =
        NetonHttpClient.createWithEngine(factoryOf(engine))

    private fun factoryOf(engine: MockEngine) = object : HttpClientEngineFactory<MockEngineConfig> {
        override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = engine
    }

    @Test fun nonStreamGenerateMapsRequestAndResponseEndToEnd() = runTest {
        var capturedUrl: String? = null
        var capturedAuth: String? = null
        var capturedBody: String? = null
        val engine = MockEngine { req ->
            capturedUrl = req.url.toString()
            capturedAuth = req.headers["Authorization"]
            capturedBody = req.body.toByteArray().decodeToString()
            respond(
                content = """{"choices":[{"message":{"role":"assistant","content":"hello"},"finish_reason":"stop"}],"usage":{"prompt_tokens":5,"completion_tokens":2,"total_tokens":7}}""",
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", "application/json"),
            )
        }
        val client = httpClient(engine)
        val provider = OpenAiCompatibleProvider(
            id = "openai", httpClient = client,
            baseUrl = "https://api.openai.com/v1", apiKey = "sk-test",
        )
        val model = provider.textModel("gpt-4o-mini")
        val resp = model.generate(ProviderCallRequest(
            messages = listOf(AiMessage(AiRole.User, listOf(AiContent.Text("hi")))),
            tools = emptyList(), toolChoice = ToolChoice.Auto,
            temperature = null, maxTokens = null, topP = null,
            stopSequences = emptyList(), metadata = emptyMap(),
        ))
        assertEquals("https://api.openai.com/v1/chat/completions", capturedUrl)
        assertEquals("Bearer sk-test", capturedAuth)
        assertTrue(capturedBody!!.contains("\"model\":\"gpt-4o-mini\""))
        assertTrue(capturedBody!!.contains("\"role\":\"user\""))
        assertEquals("hello", resp.text)
        assertEquals(AiFinishReason.Stop, resp.finishReason)
        assertEquals(AiUsage(5, 2, 7), resp.usage)
        client.close()
    }

    @Test fun nonStreamGenerateMapsToolCallResponse() = runTest {
        val engine = MockEngine { _ ->
            respond(
                content = """{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"c1","type":"function","function":{"name":"get_balance","arguments":"{\"userId\":7}"}}]},"finish_reason":"tool_calls"}]}""",
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", "application/json"),
            )
        }
        val client = httpClient(engine)
        val provider = OpenAiCompatibleProvider("openai", client, "https://x", "sk")
        val resp = provider.textModel("m").generate(ProviderCallRequest(
            messages = listOf(AiMessage(AiRole.User, listOf(AiContent.Text("balance?")))),
            tools = listOf(AiToolDefinition("get_balance", "", "{}", AiToolExecutor { "" })),
            toolChoice = ToolChoice.Auto,
            temperature = null, maxTokens = null, topP = null,
            stopSequences = emptyList(), metadata = emptyMap(),
        ))
        assertEquals(1, resp.toolCalls.size)
        assertEquals("get_balance", resp.toolCalls[0].name)
        assertEquals("""{"userId":7}""", resp.toolCalls[0].argumentsJson)
        assertEquals(AiFinishReason.ToolCalls, resp.finishReason)
        client.close()
    }

    @Test fun http429MapsToAiErrorRateLimited() = runTest {
        val engine = MockEngine { _ ->
            respond("""{"error":{"message":"slow down"}}""", HttpStatusCode.TooManyRequests)
        }
        val client = httpClient(engine)
        val provider = OpenAiCompatibleProvider("openai", client, "https://x", "sk")
        val model = provider.textModel("m")
        val ex = kotlin.test.assertFailsWith<neton.ai.AiException> {
            model.generate(ProviderCallRequest(
                messages = listOf(AiMessage(AiRole.User, listOf(AiContent.Text("x")))),
                tools = emptyList(), toolChoice = ToolChoice.Auto,
                temperature = null, maxTokens = null, topP = null,
                stopSequences = emptyList(), metadata = emptyMap(),
            ))
        }
        assertTrue(ex.error is neton.ai.AiError.RateLimited)
        client.close()
    }
}
