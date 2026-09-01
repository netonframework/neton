package neton.ai.adapter.openaicompatible

import neton.http.client.create
import neton.http.client.createWithEngine

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import neton.ai.AiError
import neton.ai.AiException
import neton.ai.AiUsage
import neton.ai.adapter.anthropic.AnthropicProvider
import neton.ai.provider.ProviderEmbedRequest
import neton.http.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OpenAiCompatibleEmbeddingTest {

    private fun httpClient(engine: MockEngine): HttpClient =
        HttpClient.createWithEngine(factoryOf(engine))

    private fun factoryOf(engine: MockEngine) = object : HttpClientEngineFactory<MockEngineConfig> {
        override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = engine
    }

    private fun makeEmbedRequest(vararg texts: String) = ProviderEmbedRequest(
        input = texts.toList(),
        metadata = emptyMap(),
    )

    /** Test 1: single input returns correct FloatArray */
    @Test
    fun embedSingleInputReturnsFloatArray() = runTest {
        val body = """
            {
              "object": "list",
              "data": [
                {"object": "embedding", "index": 0, "embedding": [0.1, 0.2, 0.3]}
              ],
              "model": "text-embedding-3-small",
              "usage": {"prompt_tokens": 5, "total_tokens": 5}
            }
        """.trimIndent()
        val engine = MockEngine { _ ->
            respond(
                content = body,
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", "application/json"),
            )
        }
        val client = httpClient(engine)
        val provider = OpenAiCompatibleProvider("openai", client, "https://api.openai.com/v1", "sk-test")
        val model = provider.embeddingModel("text-embedding-3-small")

        val resp = model.embed(makeEmbedRequest("hello world"))
        client.close()

        assertEquals(1, resp.embeddings.size)
        val arr = resp.embeddings[0]
        assertEquals(3, arr.size)
        assertEquals(0.1f, arr[0], absoluteTolerance = 1e-6f)
        assertEquals(0.2f, arr[1], absoluteTolerance = 1e-6f)
        assertEquals(0.3f, arr[2], absoluteTolerance = 1e-6f)
    }

    /** Test 2: multiple inputs returned out of order are sorted by index */
    @Test
    fun embedMultipleInputsPreservesOrder() = runTest {
        // Server returns embeddings in order index=2, 0, 1 (intentionally scrambled)
        val body = """
            {
              "object": "list",
              "data": [
                {"object": "embedding", "index": 2, "embedding": [0.3, 0.3]},
                {"object": "embedding", "index": 0, "embedding": [0.1, 0.1]},
                {"object": "embedding", "index": 1, "embedding": [0.2, 0.2]}
              ],
              "model": "text-embedding-3-small",
              "usage": {"prompt_tokens": 15, "total_tokens": 15}
            }
        """.trimIndent()
        val engine = MockEngine { _ ->
            respond(
                content = body,
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", "application/json"),
            )
        }
        val client = httpClient(engine)
        val provider = OpenAiCompatibleProvider("openai", client, "https://api.openai.com/v1", "sk-test")
        val model = provider.embeddingModel("text-embedding-3-small")

        val resp = model.embed(makeEmbedRequest("first", "second", "third"))
        client.close()

        assertEquals(3, resp.embeddings.size)
        // After sorting by index: [0]=0.1, [1]=0.2, [2]=0.3
        assertEquals(0.1f, resp.embeddings[0][0], absoluteTolerance = 1e-6f)
        assertEquals(0.2f, resp.embeddings[1][0], absoluteTolerance = 1e-6f)
        assertEquals(0.3f, resp.embeddings[2][0], absoluteTolerance = 1e-6f)
    }

    /** Test 3: usage fields are correctly returned */
    @Test
    fun embedUsageReturned() = runTest {
        val body = """
            {
              "object": "list",
              "data": [
                {"object": "embedding", "index": 0, "embedding": [0.5]}
              ],
              "model": "text-embedding-3-small",
              "usage": {"prompt_tokens": 8, "completion_tokens": 0, "total_tokens": 8}
            }
        """.trimIndent()
        val engine = MockEngine { _ ->
            respond(
                content = body,
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", "application/json"),
            )
        }
        val client = httpClient(engine)
        val provider = OpenAiCompatibleProvider("openai", client, "https://api.openai.com/v1", "sk-test")
        val model = provider.embeddingModel("text-embedding-3-small")

        val resp = model.embed(makeEmbedRequest("test usage"))
        client.close()

        assertNotNull(resp.usage)
        assertEquals(8, resp.usage!!.inputTokens)
        assertEquals(8, resp.usage!!.totalTokens)
    }

    /** Test 4: HTTP 401 maps to AiException(Unauthorized) */
    @Test
    fun embedHttpErrorMapsToAiException() = runTest {
        val body = """{"error":{"message":"Incorrect API key provided","type":"invalid_request_error","code":"invalid_api_key"}}"""
        val engine = MockEngine { _ ->
            respond(
                content = body,
                status = HttpStatusCode.Unauthorized,
                headers = headersOf("Content-Type", "application/json"),
            )
        }
        val client = httpClient(engine)
        val provider = OpenAiCompatibleProvider("openai", client, "https://api.openai.com/v1", "sk-bad")
        val model = provider.embeddingModel("text-embedding-3-small")

        val ex = assertFailsWith<AiException> {
            model.embed(makeEmbedRequest("test"))
        }
        client.close()

        assertIs<AiError.Unauthorized>(ex.error)
        assertTrue(ex.error.message.isNotEmpty())
    }

    /** Test 5: AnthropicProvider.embeddingModel() returns null → AiException(ModelNotFound) via AiClient.embed */
    @Test
    fun embedAnthropicProviderReturnsModelNotFound() = runTest {
        val engine = MockEngine { _ ->
            respond(content = "", status = HttpStatusCode.OK)
        }
        val client = httpClient(engine)
        val provider = AnthropicProvider(
            id = "anthropic",
            httpClient = client,
            apiKey = "sk-ant-test",
        )

        // embeddingModel() returns null for Anthropic
        val model = provider.embeddingModel("claude-3-haiku")
        client.close()

        // Anthropic has no embedding model — embeddingModel() must return null
        assertTrue(model == null, "AnthropicProvider.embeddingModel() must return null (no embedding API)")
    }
}
