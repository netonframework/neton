package neton.ai.adapter

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import neton.ai.AiContent
import neton.ai.AiFinishReason
import neton.ai.AiMessage
import neton.ai.AiModelId
import neton.ai.AiStreamEvent
import neton.ai.AiToolDefinition
import neton.ai.GenerateTextRequest
import neton.ai.adapter.anthropic.AnthropicProvider
import neton.ai.adapter.gemini.GeminiProvider
import neton.ai.adapter.openaicompatible.OpenAiApi
import neton.ai.adapter.openaicompatible.OpenAiCompatibleProvider
import neton.ai.internal.DefaultModelRouter
import neton.ai.internal.DefaultProviderRegistry
import neton.ai.internal.runToolLoop
import neton.ai.protocol.ir.ProviderMetadata
import neton.ai.protocol.ir.ReasoningOptions
import neton.ai.protocol.profile.ProviderProfiles
import neton.ai.protocol.usage.UsageCompleteness
import neton.ai.provider.AiProvider
import neton.ai.provider.ProviderCallRequest
import neton.ai.routing.RoutingConfig
import neton.ai.testkit.bodyText
import neton.ai.testkit.jsonResponse
import neton.ai.testkit.sseChunks
import neton.ai.usage.NoopAiUsageRecorder
import neton.http.client.HttpClientMethod
import neton.http.testkit.ScriptedHttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProtocolProvidersTest {

    private val echo = AiToolDefinition("get", "get a value", """{"type":"object"}""") { """{"v":42}""" }

    private suspend fun loop(provider: AiProvider, model: String, req: GenerateTextRequest) = runToolLoop(
        request = req.copy(model = AiModelId(provider.id, model)),
        registry = DefaultProviderRegistry(mapOf(provider.id to provider)),
        router = DefaultModelRouter(RoutingConfig()),
        recorder = NoopAiUsageRecorder,
    )

    private fun body(text: String?): JsonObject = Json.parseToJsonElement(text!!).jsonObject

    /** P0：Anthropic 开启 thinking 时，第二轮必须把 thinking 块原样放在 tool_use 前面发回。 */
    @Test
    fun anthropicToolLoopSendsThinkingBackInOrder() = runTest {
        var round = 0
        val http = ScriptedHttpClient().on(HttpClientMethod.Post, "") {
            round++
            if (round == 1) jsonResponse(200, """
                {"id":"m1","type":"message","role":"assistant","model":"claude","stop_reason":"tool_use",
                 "content":[{"type":"thinking","thinking":"need data","signature":"SIG1"},{"type":"text","text":"checking"},
                            {"type":"tool_use","id":"toolu_1","name":"get","input":{}}],
                 "usage":{"input_tokens":10,"output_tokens":5}}""")
            else jsonResponse(200, """
                {"id":"m2","type":"message","role":"assistant","model":"claude","stop_reason":"end_turn",
                 "content":[{"type":"text","text":"42"}],"usage":{"input_tokens":20,"output_tokens":2}}""")
        }
        val provider = AnthropicProvider("anthropic", http, apiKey = "k")
        val result = loop(provider, "claude-sonnet-4-5", GenerateTextRequest(
            messages = listOf(AiMessage.user("what is it?")), tools = listOf(echo), maxTokens = 4096,
            reasoning = ReasoningOptions(budgetTokens = 2048),
        ))
        assertEquals("42", result.text)
        val second = body(http.recorded[1].bodyText())
        val assistant = second["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("thinking", "text", "tool_use"), assistant.map { it["type"]!!.jsonPrimitive.content })
        assertEquals("SIG1", assistant[0]["signature"]!!.jsonPrimitive.content)
        val toolResult = second["messages"]!!.jsonArray[2].jsonObject["content"]!!.jsonArray[0].jsonObject
        assertEquals("toolu_1", toolResult["tool_use_id"]!!.jsonPrimitive.content)
        assertEquals(2048, body(http.recorded[0].bodyText())["thinking"]!!.jsonObject["budget_tokens"]!!.jsonPrimitive.content.toInt())
    }

    /** Gemini 3 的 thought signature 在工具循环里原样回传。 */
    @Test
    fun geminiToolLoopRoundTripsThoughtSignature() = runTest {
        var round = 0
        val http = ScriptedHttpClient().on(HttpClientMethod.Post, "") {
            round++
            if (round == 1) jsonResponse(200, """
                {"candidates":[{"content":{"role":"model","parts":[{"functionCall":{"name":"get","args":{}},"thoughtSignature":"GSIG"}]},"finishReason":"STOP"}],
                 "usageMetadata":{"promptTokenCount":5,"candidatesTokenCount":1}}""")
            else jsonResponse(200, """{"candidates":[{"content":{"role":"model","parts":[{"text":"42"}]},"finishReason":"STOP"}]}""")
        }
        val provider = GeminiProvider("gemini", http, apiKey = "gk")
        val result = loop(provider, "gemini-3-pro", GenerateTextRequest(messages = listOf(AiMessage.user("q")), tools = listOf(echo)))
        assertEquals("42", result.text)
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-3-pro:generateContent", http.recorded[0].url)
        assertEquals("gk", http.recorded[0].headers.get("x-goog-api-key"))
        val contents = body(http.recorded[1].bodyText())["contents"]!!.jsonArray
        val call = contents[1].jsonObject["parts"]!!.jsonArray[0].jsonObject
        assertEquals("GSIG", call["thoughtSignature"]!!.jsonPrimitive.content)
        val response = contents[2].jsonObject["parts"]!!.jsonArray[0].jsonObject["functionResponse"]!!.jsonObject
        assertEquals("get", response["name"]!!.jsonPrimitive.content)
        assertEquals(JsonPrimitive(42), response["response"]!!.jsonObject["v"])
    }

    @Test
    fun interleavedParallelToolDeltasAccumulatePerCall() = runTest {
        val sse = listOf(
            """{"id":"c","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"a","type":"function","function":{"name":"x","arguments":""}}]}}]}""",
            """{"id":"c","choices":[{"index":0,"delta":{"tool_calls":[{"index":1,"id":"b","type":"function","function":{"name":"y","arguments":"{\"k\":"}}]}}]}""",
            """{"id":"c","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"j\":1}"}}]}}]}""",
            """{"id":"c","choices":[{"index":0,"delta":{"tool_calls":[{"index":1,"function":{"arguments":"2}"}}]}}]}""",
            """{"id":"c","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}""",
            "[DONE]",
        ).joinToString("") { "data: $it\n\n" }
        val http = ScriptedHttpClient().onStream(HttpClientMethod.Post, "") { sseChunks(sse) }
        val model = OpenAiCompatibleProvider("o", http, "https://api.openai.com/v1", "k").streamingTextModel("gpt")
        val events = model.stream(req()).toList()
        val ready = events.filterIsInstance<AiStreamEvent.ToolCallReady>().associate { it.call.id to it.call.argumentsJson }
        assertEquals(mapOf("a" to """{"j":1}""", "b" to """{"k":2}"""), ready)
        val completed = events.filterIsInstance<AiStreamEvent.Completed>().single()
        assertEquals(AiFinishReason.ToolCalls, completed.finishReason)
        assertEquals(listOf("a", "b"), completed.message.toolCalls.map { it.id })
    }

    @Test
    fun streamWithoutUsageReportsUnknownNotZero() = runTest {
        val sse = """data: {"id":"c","choices":[{"index":0,"delta":{"content":"hi"},"finish_reason":"stop"}]}""" + "\n\ndata: [DONE]\n\n"
        val http = ScriptedHttpClient().onStream(HttpClientMethod.Post, "") { sseChunks(sse) }
        val model = OpenAiCompatibleProvider("o", http, "https://example.com/v1", "k").streamingTextModel("m")
        val completed = model.stream(req()).toList().filterIsInstance<AiStreamEvent.Completed>().single()
        assertNull(completed.usage)
        // 未知厂商：不发 stream_options
        assertFalse("stream_options" in body(http.recorded[0].bodyText()))
    }

    @Test
    fun truncatedStreamUsageIsPartial() = runTest {
        val sse = """data: {"id":"c","choices":[{"index":0,"delta":{"content":"hi"}}],"usage":{"prompt_tokens":3,"completion_tokens":1}}""" + "\n\n"
        val http = ScriptedHttpClient().onStream(HttpClientMethod.Post, "") { sseChunks(sse) }
        val model = OpenAiCompatibleProvider("o", http, "https://api.deepseek.com", "k").streamingTextModel("m")
        val completed = model.stream(req()).toList().filterIsInstance<AiStreamEvent.Completed>().single()
        assertEquals(UsageCompleteness.PARTIAL, completed.usage?.completeness)
        assertEquals(3, completed.usage?.inputTokens)
    }

    @Test
    fun profilesShapeTheRequest() = runTest {
        val http = ScriptedHttpClient().onStream(HttpClientMethod.Post, "") { sseChunks("data: [DONE]\n\n") }
        // OpenAI：max_completion_tokens + include_usage
        OpenAiCompatibleProvider("o", http, "https://api.openai.com/v1", "k").streamingTextModel("gpt").stream(req(maxTokens = 50)).toList()
        val openai = body(http.recorded[0].bodyText())
        assertEquals(50, openai["max_completion_tokens"]!!.jsonPrimitive.content.toInt())
        assertFalse("max_tokens" in openai)
        assertEquals(JsonPrimitive(true), openai["stream_options"]!!.jsonObject["include_usage"])
        // Mistral：拒收未知字段，不发 stream_options
        OpenAiCompatibleProvider("m", http, "https://api.mistral.ai/v1", "k").streamingTextModel("mistral").stream(req(maxTokens = 50)).toList()
        val mistral = body(http.recorded[1].bodyText())
        assertEquals(50, mistral["max_tokens"]!!.jsonPrimitive.content.toInt())
        assertFalse("stream_options" in mistral)
        assertEquals(ProviderProfiles.QWEN, ProviderProfiles.forBaseUrl("https://ws1.cn-beijing.maas.aliyuncs.com/compatible-mode/v1"))
        assertEquals(ProviderProfiles.CONSERVATIVE, ProviderProfiles.forBaseUrl("http://10.0.0.5:8000/v1"))
    }

    @Test
    fun providerOptionsMergeWithoutOverridingReservedFields() = runTest {
        val http = ScriptedHttpClient().on(HttpClientMethod.Post, "") {
            jsonResponse(200, """{"id":"c","object":"chat.completion","created":1,"model":"m","choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}""")
        }
        val provider = OpenAiCompatibleProvider("qwen", http, "https://dashscope.aliyuncs.com/compatible-mode/v1", "k")
        loop(provider, "qwen-max", GenerateTextRequest(
            messages = listOf(AiMessage.user("hi")),
            providerOptions = mapOf(
                "qwen" to JsonObject(mapOf("enable_thinking" to JsonPrimitive(true), "model" to JsonPrimitive("hijack"))),
                "other" to JsonObject(mapOf("x" to JsonPrimitive(1))),
            ),
        ))
        val sent = body(http.recorded[0].bodyText())
        assertEquals(JsonPrimitive(true), sent["enable_thinking"])
        assertEquals("qwen-max", sent["model"]!!.jsonPrimitive.content)
        assertFalse("x" in sent)
    }

    @Test
    fun responsesApiProvider() = runTest {
        val http = ScriptedHttpClient().on(HttpClientMethod.Post, "") {
            jsonResponse(200, """
                {"id":"resp_1","object":"response","status":"completed","model":"gpt-5",
                 "output":[{"type":"reasoning","id":"rs_1","summary":[{"type":"summary_text","text":"plan"}],"encrypted_content":"ENC"},
                           {"type":"message","role":"assistant","id":"msg_1","content":[{"type":"output_text","text":"done","annotations":[]}]}],
                 "usage":{"input_tokens":10,"input_tokens_details":{"cached_tokens":4},"output_tokens":5,"output_tokens_details":{"reasoning_tokens":3},"total_tokens":15}}""")
        }
        val provider = OpenAiCompatibleProvider("openai", http, "https://api.openai.com/v1", "k", api = OpenAiApi.Responses)
        val resp = provider.textModel("gpt-5").generate(req())
        assertEquals("https://api.openai.com/v1/responses", http.recorded[0].url)
        assertEquals("done", resp.text)
        val reasoning = resp.message.content.first() as AiContent.Reasoning
        assertEquals("ENC", reasoning.encryptedContent)
        assertEquals("rs_1", reasoning.metadata.string(ProviderMetadata.OPENAI, ProviderMetadata.ITEM_ID))
        assertEquals(4, resp.usage?.cachedInputTokens)
        assertEquals(3, resp.usage?.reasoningTokens)
        assertEquals(15, resp.usage?.totalTokens)
        assertTrue("input" in body(http.recorded[0].bodyText()))
    }

    private fun req(maxTokens: Int? = null) = ProviderCallRequest(
        messages = listOf(AiMessage.user("hi")), tools = emptyList(), toolChoice = neton.ai.ToolChoice.Auto,
        temperature = null, maxTokens = maxTokens, topP = null, stopSequences = emptyList(), metadata = emptyMap(),
    )
}
