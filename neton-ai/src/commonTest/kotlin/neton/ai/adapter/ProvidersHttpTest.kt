package neton.ai.adapter

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import neton.ai.*
import neton.ai.protocol.ir.ProviderMetadata
import neton.ai.protocol.usage.UsageCompleteness
import neton.ai.provider.SpeechOptions
import neton.ai.provider.TranscriptionOptions
import neton.ai.testkit.bodyText
import neton.ai.testkit.jsonResponse
import neton.ai.testkit.sseChunks
import neton.ai.testkit.streamHttpError
import neton.http.client.HttpClientMethod
import neton.http.testkit.ScriptedHttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 整条链路（AiClient → 循环 → 协议层 → HTTP）的 provider 行为。 */
class ProvidersHttpTest {

    private fun body(text: String?): JsonObject = Json.parseToJsonElement(text!!).jsonObject

    private fun client(http: ScriptedHttpClient, defaultModel: String, block: neton.ai.config.ProvidersBuilder.() -> Unit) = AiClient.create {
        httpClient = http
        providers(block)
        routing { this.defaultModel = defaultModel }
    }

    private val get = AiTools.function("get", "get a value") { _, _ -> """{"v":42}""" }

    @Test
    fun anthropicHeadersAndToolLoopSendThinkingBackInOrder() = runTest {
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
        val ai = client(http, "anthropic:claude-sonnet-4-5") { anthropic("anthropic") { apiKey = "sk-ant" } }
        val result = ai.generate {
            user("what is it?"); tool(get); maxOutputTokens = 4096; reasoningBudgetTokens = 2048
        }
        assertEquals("42", result.text)
        assertEquals(2, result.steps.size)
        val first = http.recorded[0]
        assertEquals("https://api.anthropic.com/v1/messages", first.url)
        assertEquals("sk-ant", first.headers.get("x-api-key"))
        assertNull(first.headers.get("Authorization"))
        assertEquals(2048, body(first.bodyText())["thinking"]!!.jsonObject["budget_tokens"]!!.jsonPrimitive.content.toInt())
        val second = body(http.recorded[1].bodyText())
        val assistant = second["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("thinking", "text", "tool_use"), assistant.map { it["type"]!!.jsonPrimitive.content })
        assertEquals("SIG1", assistant[0]["signature"]!!.jsonPrimitive.content)
        // 合计用量：两步之和，口径为含缓存的全部输入
        assertEquals(30L, result.usage.inputTokens)
        assertEquals(7L, result.usage.outputTokens)
    }

    @Test
    fun geminiToolLoopRoundTripsThoughtSignature() = runTest {
        var round = 0
        val http = ScriptedHttpClient().on(HttpClientMethod.Post, "") {
            round++
            if (round == 1) jsonResponse(200, """
                {"candidates":[{"content":{"role":"model","parts":[{"functionCall":{"name":"get","args":{}},"thoughtSignature":"GSIG"}]},"finishReason":"STOP"}]}""")
            else jsonResponse(200, """{"candidates":[{"content":{"role":"model","parts":[{"text":"42"}]},"finishReason":"STOP"}]}""")
        }
        val ai = client(http, "gemini:gemini-3-pro") { gemini("gemini") { apiKey = "gk" } }
        val result = ai.generate { user("q"); tool(get) }
        assertEquals("42", result.text)
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-3-pro:generateContent", http.recorded[0].url)
        val contents = body(http.recorded[1].bodyText())["contents"]!!.jsonArray
        assertEquals("GSIG", contents[1].jsonObject["parts"]!!.jsonArray[0].jsonObject["thoughtSignature"]!!.jsonPrimitive.content)
        val response = contents[2].jsonObject["parts"]!!.jsonArray[0].jsonObject["functionResponse"]!!.jsonObject
        assertEquals("get", response["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun streamingInterleavedToolDeltasAndPartsSequence() = runTest {
        val sse = listOf(
            """{"id":"c","choices":[{"index":0,"delta":{"content":"Hi"}}]}""",
            """{"id":"c","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"a","type":"function","function":{"name":"get","arguments":""}}]}}]}""",
            """{"id":"c","choices":[{"index":0,"delta":{"tool_calls":[{"index":1,"id":"b","type":"function","function":{"name":"get","arguments":"{\"k\":"}}]}}]}""",
            """{"id":"c","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"j\":1}"}}]}}]}""",
            """{"id":"c","choices":[{"index":0,"delta":{"tool_calls":[{"index":1,"function":{"arguments":"2}"}}]}}]}""",
            """{"id":"c","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}""",
            "[DONE]",
        ).joinToString("") { "data: $it\n\n" }
        val http = ScriptedHttpClient().onStream(HttpClientMethod.Post, "") { sseChunks(sse) }
        val ai = client(http, "o:gpt") { openAiCompatible("o") { baseUrl = "https://api.openai.com/v1"; apiKey = "k" } }
        val parts = ai.stream { user("hi"); tool(AiTool("get", inputSchema = AiTool.EMPTY_OBJECT_SCHEMA)) }.toList()
        val types = parts.map { it::class.simpleName }
        assertEquals("StepStart", types.first())
        assertEquals("Finish", types.last())
        assertTrue(types.indexOf("TextStart") < types.indexOf("TextDelta"))
        val calls = parts.filterIsInstance<AiStreamPart.ToolCall>().associate { it.call.call.id to it.call.call.argumentsJson }
        assertEquals(mapOf("a" to """{"j":1}""", "b" to """{"k":2}"""), calls)
        val result = (parts.last() as AiStreamPart.Finish).result
        // 没有执行器：交还调用方
        assertEquals(listOf("a", "b"), result.pendingToolCalls.map { it.id })
        assertEquals(AiFinishReason.ToolCalls, result.finishReason)
    }

    @Test
    fun streamUsageUnknownWhenAbsentAndPartialWhenTruncated() = runTest {
        val full = """data: {"id":"c","choices":[{"index":0,"delta":{"content":"hi"},"finish_reason":"stop"}]}""" + "\n\ndata: [DONE]\n\n"
        val http = ScriptedHttpClient().onStream(HttpClientMethod.Post, "") { sseChunks(full) }
        val ai = client(http, "o:m") { openAiCompatible("o") { baseUrl = "https://example.com/v1"; apiKey = "k" } }
        val r = ai.stream { user("hi") }.result()
        assertNull(r.usage.inputTokens)
        assertEquals(UsageCompleteness.UNKNOWN, r.usage.completeness)
        // 未知厂商：不发 stream_options
        assertFalse("stream_options" in body(http.recorded[0].bodyText()))

        val cut = """data: {"id":"c","choices":[{"index":0,"delta":{"content":"hi"}}],"usage":{"prompt_tokens":3,"completion_tokens":1}}""" + "\n\n"
        val http2 = ScriptedHttpClient().onStream(HttpClientMethod.Post, "") { sseChunks(cut) }
        val r2 = client(http2, "o:m") { openAiCompatible("o") { baseUrl = "https://api.deepseek.com"; apiKey = "k" } }.stream { user("hi") }.result()
        assertEquals(UsageCompleteness.PARTIAL, r2.usage.completeness)
        assertEquals(3L, r2.usage.inputTokens)
    }

    @Test
    fun profilesAndProviderOptionsShapeTheRequest() = runTest {
        val http = ScriptedHttpClient().onStream(HttpClientMethod.Post, "") { sseChunks("data: [DONE]\n\n") }
        client(http, "o:gpt") { openAiCompatible("o") { baseUrl = "https://api.openai.com/v1"; apiKey = "k" } }
            .stream { user("hi"); maxOutputTokens = 50; reasoning = AiReasoning.High }.toList()
        val openai = body(http.recorded[0].bodyText())
        assertEquals(50, openai["max_completion_tokens"]!!.jsonPrimitive.content.toInt())
        assertEquals("high", openai["reasoning_effort"]!!.jsonPrimitive.content)
        assertEquals(JsonPrimitive(true), openai["stream_options"]!!.jsonObject["include_usage"])

        val http2 = ScriptedHttpClient().on(HttpClientMethod.Post, "") {
            jsonResponse(200, """{"id":"c","object":"chat.completion","created":1,"model":"m","choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}""")
        }
        client(http2, "qwen:qwen-max") { openAiCompatible("qwen") { baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1"; apiKey = "k" } }.generate {
            user("hi")
            providerOptions("qwen", JsonObject(mapOf("enable_thinking" to JsonPrimitive(true), "model" to JsonPrimitive("hijack"))))
            providerOptions("other", JsonObject(mapOf("x" to JsonPrimitive(1))))
        }
        val sent = body(http2.recorded[0].bodyText())
        assertEquals(JsonPrimitive(true), sent["enable_thinking"])
        assertEquals("qwen-max", sent["model"]!!.jsonPrimitive.content)
        assertFalse("x" in sent)
    }

    @Test
    fun responsesApiCarriesEncryptedReasoningAndDetailedUsage() = runTest {
        val http = ScriptedHttpClient().on(HttpClientMethod.Post, "") {
            jsonResponse(200, """
                {"id":"resp_1","object":"response","status":"completed","model":"gpt-5",
                 "output":[{"type":"reasoning","id":"rs_1","summary":[{"type":"summary_text","text":"plan"}],"encrypted_content":"ENC"},
                           {"type":"message","role":"assistant","id":"msg_1","content":[{"type":"output_text","text":"done","annotations":[{"type":"url_citation","url":"https://ex.com","title":"Ex"}]}]}],
                 "usage":{"input_tokens":10,"input_tokens_details":{"cached_tokens":4},"output_tokens":5,"output_tokens_details":{"reasoning_tokens":3},"total_tokens":15}}""")
        }
        val ai = client(http, "openai:gpt-5") { openAiCompatible("openai") { baseUrl = "https://api.openai.com/v1"; apiKey = "k"; api = "responses" } }
        val r = ai.generate { user("hi") }
        assertEquals("https://api.openai.com/v1/responses", http.recorded[0].url)
        assertEquals("done", r.text)
        val reasoning = r.content.first() as AiContent.Reasoning
        assertEquals("ENC", reasoning.encryptedContent)
        assertEquals("rs_1", reasoning.metadata.string(ProviderMetadata.OPENAI, ProviderMetadata.ITEM_ID))
        assertEquals(4L, r.usage.inputTokenDetails.cacheReadTokens)
        assertEquals(6L, r.usage.inputTokenDetails.noCacheTokens)
        assertEquals(3L, r.usage.outputTokenDetails.reasoningTokens)
        assertEquals(2L, r.usage.outputTokenDetails.textTokens)
        assertEquals(15L, r.usage.totalTokens)
        assertEquals(listOf("https://ex.com"), r.sources.map { it.url })
    }

    @Test
    fun errorsAreClassified() = runTest {
        val http401 = ScriptedHttpClient().on(HttpClientMethod.Post, "") {
            jsonResponse(401, """{"type":"error","error":{"type":"authentication_error","message":"invalid api key"}}""")
        }
        val e1 = assertFailsWith<AiException> { client(http401, "a:c") { anthropic("a") { apiKey = "bad" } }.generate { user("x"); maxRetries = 0 } }
        assertIs<AiError.Unauthorized>(e1.error)

        val http429 = ScriptedHttpClient().onStream(HttpClientMethod.Post, "") {
            streamHttpError(429, """{"error":{"message":"Rate limit exceeded, retry later","type":"rate_limit_error"}}""")
        }
        val parts = client(http429, "o:m") { openAiCompatible("o") { baseUrl = "https://x"; apiKey = "k" } }.stream { user("x"); maxRetries = 0 }.toList()
        val err = (parts.last() as AiStreamPart.Error).error
        assertIs<AiError.RateLimited>(err)
        assertTrue("Rate limit exceeded" in err.message)
    }

    @Test
    fun mediaEndpoints() = runTest {
        val http = ScriptedHttpClient()
            .on(HttpClientMethod.Post, "https://api.openai.com/v1/images") {
                jsonResponse(200, """{"data":[{"b64_json":"iVBORw0KGgo="}],"usage":{"input_tokens":5,"output_tokens":100}}""")
            }
            .on(HttpClientMethod.Post, "https://api.openai.com/v1/audio/transcriptions") {
                jsonResponse(200, """{"text":"hello","language":"en","duration":1.5,"segments":[{"text":" hello","start":0.0,"end":1.5}]}""")
            }
            .on(HttpClientMethod.Post, "https://api.openai.com/v1/rerank") {
                jsonResponse(200, """{"results":[{"index":1,"relevance_score":0.9},{"index":0,"relevance_score":0.2}]}""")
            }
            .on(HttpClientMethod.Post, "https://api.openai.com/v1/embeddings") {
                val n = body(it.bodyText())["input"]!!.jsonArray.size
                jsonResponse(200, """{"data":[${(0 until n).joinToString(",") { i -> """{"index":$i,"embedding":[$i.0,1.0]}""" }}],"usage":{"prompt_tokens":$n}}""")
            }
            .onStream(HttpClientMethod.Post, "https://api.openai.com/v1/audio/speech") {
                listOf(neton.http.client.HttpClientStreamChunk.Bytes(byteArrayOf(1, 2, 3, -1)),
                    neton.http.client.HttpClientStreamChunk.End(neton.core.http.HttpHeaders.of()))
            }
        val ai = client(http, "openai:gpt") { openAiCompatible("openai") { baseUrl = "https://api.openai.com/v1"; apiKey = "k" } }
        val img = ai.generateImage(ImageRequest(AiModelId.parse("openai:gpt-image-1"), "a cat", n = 1))
        assertEquals("image/png", img.image.mediaType)
        assertEquals(100L, img.usage.outputTokens)

        val stt = ai.transcribe(AiModelId.parse("openai:whisper-1"), TranscriptionOptions(byteArrayOf(0, 1), "audio/wav"))
        assertEquals("hello", stt.text)
        assertEquals(1.5, stt.durationInSeconds)
        assertEquals("hello", stt.segments.single().text)
        val upload = http.recorded.first { it.url.endsWith("/audio/transcriptions") }.body as neton.http.client.HttpClientBody.Bytes
        assertTrue(upload.contentType.startsWith("multipart/form-data; boundary="))
        assertTrue("name=\"model\"\r\n\r\nwhisper-1" in upload.bytes.decodeToString())

        val ranked = ai.rerank(AiModelId.parse("openai:rerank-1"), "q", listOf("a", "b"))
        assertEquals(listOf(1, 0), ranked.ranking.map { it.index })

        val speech = ai.generateSpeech(AiModelId.parse("openai:tts-1"), SpeechOptions("hi"))
        assertEquals(listOf<Byte>(1, 2, 3, -1), speech.audio.bytes.toList())
        assertEquals("audio/mpeg", speech.audio.mediaType)

        // 批量嵌入按单次上限切分（OpenAI 2048 条一批），顺序与输入一致
        val values = (0 until 3000).map { "v$it" }
        val emb = ai.embed(EmbeddingRequest(AiModelId.parse("openai:text-embedding-3-small"), values))
        assertEquals(3000, emb.embeddings.size)
        assertEquals(2, http.recorded.count { it.url.endsWith("/embeddings") })
        // 假服务端在每批内从 0 编号：第二批最后一条是 2999 - 2048
        assertEquals(951f, emb.embeddings.last()[0])
        assertEquals(2047f, emb.embeddings[2047][0])
        assertEquals(3000L, emb.usage.inputTokens)
    }
}
