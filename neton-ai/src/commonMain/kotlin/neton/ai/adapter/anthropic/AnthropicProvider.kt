// neton-ai/src/commonMain/kotlin/neton/ai/adapter/anthropic/AnthropicProvider.kt
package neton.ai.adapter.anthropic

import neton.ai.provider.AiEmbeddingModel
import neton.ai.provider.AiProvider
import neton.ai.provider.AiStreamingTextModel
import neton.ai.provider.AiTextModel
import neton.http.client.NetonHttpClient

class AnthropicProvider(
    override val id: String,
    private val httpClient: NetonHttpClient,
    private val baseUrl: String = "https://api.anthropic.com",
    private val apiKey: String,
    private val version: String = "2023-06-01",
    private val beta: List<String> = emptyList(),
    private val defaultHeaders: Map<String, String> = emptyMap(),
) : AiProvider {
    override fun textModel(modelName: String): AiTextModel = AnthropicTextModel(
        providerId = id, modelName = modelName, httpClient = httpClient,
        baseUrl = baseUrl, apiKey = apiKey, version = version, beta = beta,
        defaultHeaders = defaultHeaders,
    )
    override fun streamingTextModel(modelName: String): AiStreamingTextModel? = null   // PR2
    override fun embeddingModel(modelName: String): AiEmbeddingModel? = null            // Anthropic has no embeddings
}
