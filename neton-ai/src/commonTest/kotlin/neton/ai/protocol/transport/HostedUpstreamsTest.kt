package neton.ai.protocol.transport

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import neton.ai.protocol.obj
import neton.ai.protocol.transport.google.GoogleAccessTokens
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class HostedUpstreamsTest {
    @Test
    fun bedrockAnthropic() {
        assertEquals("/model/anthropic.claude-3%3A0/invoke-with-response-stream", BedrockUpstream.anthropicPath("anthropic.claude-3:0", stream = true))
        val body = BedrockUpstream.anthropicBody(obj("""{"model":"x","stream":true,"max_tokens":1,"messages":[]}"""))
        assertEquals(JsonPrimitive(BedrockUpstream.ANTHROPIC_VERSION), body["anthropic_version"])
        assertFalse("model" in body || "stream" in body)
    }

    @Test
    fun vertex() {
        assertEquals("/v1/projects/p/locations/us-east5/publishers/anthropic/models/claude:streamRawPredict",
            VertexUpstream.anthropicPath("p", "us-east5", "claude", stream = true))
        assertEquals("/v1/projects/p/locations/global/publishers/google/models/gemini-3-pro:streamGenerateContent?alt=sse",
            VertexUpstream.geminiPath("p", "global", "gemini-3-pro", stream = true))
        val body = VertexUpstream.anthropicBody(obj("""{"model":"x","stream":true}"""))
        assertEquals(JsonPrimitive(true), body["stream"])
        assertFalse("model" in body)
        assertEquals("us-central1", VertexUpstream.locationFromHost("us-central1-aiplatform.googleapis.com"))
        assertEquals("global", VertexUpstream.locationFromHost("aiplatform.googleapis.com"))
    }

    @Test
    fun azure() {
        assertEquals("/openai/deployments/my-gpt/chat/completions?api-version=2024-10-21", AzureUpstream.path("/v1/chat/completions", "my-gpt"))
        assertEquals("/openai/deployments/emb/embeddings?api-version=2025-01-01", AzureUpstream.path("/v1/embeddings", "emb", "2025-01-01"))
        assertEquals("/openai/v1/responses?api-version=preview", AzureUpstream.path("/v1/responses", "x"))
    }

    @Test
    fun plainAccessTokenIsUsedAsIs() = runBlocking<Unit> {
        // 非服务账号 JSON：不发起网络请求，原样返回
        val tokens = GoogleAccessTokens(client = neverCalledClient())
        assertEquals("ya29.token", tokens.bearer("  ya29.token "))
    }

    private fun neverCalledClient(): neton.http.client.HttpClient = object : neton.http.client.HttpClient {
        override suspend fun request(request: neton.http.client.HttpClientRequest) = error("must not be called")
        override fun stream(request: neton.http.client.HttpClientRequest) = error("must not be called")
        override suspend fun close() {}
        override val capabilities = emptySet<neton.http.client.HttpClientCapability>()
    }
}
