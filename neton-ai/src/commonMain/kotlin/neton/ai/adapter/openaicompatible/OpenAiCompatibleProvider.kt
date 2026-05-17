// neton-ai/src/commonMain/kotlin/neton/ai/adapter/openaicompatible/OpenAiCompatibleProvider.kt
package neton.ai.adapter.openaicompatible

import neton.ai.provider.AiEmbeddingModel
import neton.ai.provider.AiProvider
import neton.ai.provider.AiStreamingTextModel
import neton.ai.provider.AiTextModel
import neton.http.client.NetonHttpClient

class OpenAiCompatibleProvider(
    override val id: String,
    private val httpClient: NetonHttpClient,
    private val baseUrl: String,
    private val apiKey: String,
    private val organization: String? = null,
    private val defaultHeaders: Map<String, String> = emptyMap(),
) : AiProvider {
    override fun textModel(modelName: String): AiTextModel = OpenAiCompatibleTextModel(
        providerId = id,
        modelName = modelName,
        httpClient = httpClient,
        baseUrl = baseUrl,
        apiKey = apiKey,
        organization = organization,
        defaultHeaders = defaultHeaders,
    )
    override fun streamingTextModel(modelName: String): AiStreamingTextModel? = null  // PR2
    override fun embeddingModel(modelName: String): AiEmbeddingModel? = null          // PR3
}
