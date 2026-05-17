// neton-ai/src/commonMain/kotlin/neton/ai/adapter/openaicompatible/OpenAiCompatibleProvider.kt
package neton.ai.adapter.openaicompatible

import neton.ai.AiLogSink
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
    private val logSink: AiLogSink? = null,
    private val debug: Boolean = false,
) : AiProvider {
    override fun textModel(modelName: String): AiTextModel = OpenAiCompatibleTextModel(
        providerId = id,
        modelName = modelName,
        httpClient = httpClient,
        baseUrl = baseUrl,
        apiKey = apiKey,
        organization = organization,
        defaultHeaders = defaultHeaders,
        logSink = logSink,
        debug = debug,
    )
    override fun streamingTextModel(modelName: String): AiStreamingTextModel = textModel(modelName) as AiStreamingTextModel
    override fun embeddingModel(modelName: String): AiEmbeddingModel = OpenAiCompatibleEmbeddingModel(
        providerId = id, modelName = modelName, httpClient = httpClient,
        baseUrl = baseUrl, apiKey = apiKey, organization = organization,
        defaultHeaders = defaultHeaders, logSink = logSink, debug = debug,
    )
}
