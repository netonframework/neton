package neton.ai.adapter

import kotlinx.coroutines.test.runTest
import neton.ai.AiClient
import neton.ai.AiModelId
import neton.ai.AiTimeout
import neton.ai.EmbeddingRequest
import neton.ai.testkit.jsonResponse
import neton.http.client.HttpClientMethod
import neton.http.testkit.ScriptedHttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * provider 超时：AI 请求总是带着自己的 HTTP 超时发出，与应用共享 HttpClient 的默认超时无关。
 * 取值顺序：调用级 AiTimeout.requestMs > provider 配置 timeoutMillis > 默认 60s。
 */
class ProviderTimeoutTest {

    private val chatOk =
        """{"id":"c","object":"chat.completion","created":1,"model":"m","choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}"""
    private val embedOk =
        """{"object":"list","data":[{"object":"embedding","index":0,"embedding":[0.1,0.2]}],"model":"e","usage":{"prompt_tokens":1,"total_tokens":1}}"""

    private fun http() = ScriptedHttpClient().on(HttpClientMethod.Post, "") { req ->
        jsonResponse(200, if (req.url.endsWith("/embeddings")) embedOk else chatOk)
    }

    private fun client(http: ScriptedHttpClient, timeoutMillis: Long?) = AiClient.create {
        httpClient = http
        providers {
            openAiCompatible("o") {
                baseUrl = "https://example.com/v1"; apiKey = "k"
                this.timeoutMillis = timeoutMillis
            }
        }
        routing { defaultModel = "o:m" }
    }

    @Test
    fun unconfigured_provider_sends_default_60s_timeout() = runTest {
        val http = http()
        client(http, null).generate { user("hi") }
        val t = http.recorded.single().timeout!!
        assertEquals(60_000L, t.requestMillis)
        assertEquals(60_000L, t.socketMillis)
        // 连接超时沿用应用客户端的设置
        assertNull(t.connectMillis)
    }

    @Test
    fun provider_timeout_applies_to_generate_and_embeddings() = runTest {
        val http = http()
        val ai = client(http, 120_000L)
        ai.generate { user("hi") }
        ai.embed(EmbeddingRequest(AiModelId.parse("o:e"), listOf("x")))
        assertEquals(2, http.recorded.size)
        http.recorded.forEach { r ->
            assertEquals(120_000L, r.timeout!!.requestMillis, r.url)
            assertEquals(120_000L, r.timeout!!.socketMillis, r.url)
        }
        assertTrue(http.recorded.any { it.url.endsWith("/embeddings") })
    }

    @Test
    fun per_call_request_timeout_overrides_provider_timeout() = runTest {
        val http = http()
        client(http, 30_000L).generate {
            user("long batch")
            timeout = AiTimeout(requestMs = 180_000L)
        }
        val t = http.recorded.single().timeout!!
        assertEquals(180_000L, t.requestMillis)
        assertEquals(180_000L, t.socketMillis)
    }

    @Test
    fun non_positive_provider_timeout_is_rejected_by_validation() {
        assertFailsWith<neton.ai.AiException> { client(http(), 0L) }
        assertFailsWith<IllegalArgumentException> { AiTimeout(requestMs = 0) }
    }
}
