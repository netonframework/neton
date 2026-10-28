package neton.ai.adapter.anthropic

import neton.ai.AiLogSink
import neton.ai.adapter.ProtocolEndpoint
import neton.ai.adapter.ProtocolTextModel
import neton.ai.protocol.ir.WireFormat
import neton.ai.protocol.profile.ProviderProfiles
import neton.ai.model.AiLanguageModel
import neton.ai.provider.AiProvider
import neton.http.client.HttpClient

/** Anthropic Messages。鉴权用 `x-api-key`，不用 Bearer。 */
@neton.ai.ExperimentalNetonAiApi
class AnthropicProvider(
    override val id: String,
    private val httpClient: HttpClient,
    private val baseUrl: String = "https://api.anthropic.com",
    private val apiKey: String,
    private val version: String = "2023-06-01",
    private val beta: List<String> = emptyList(),
    private val defaultHeaders: Map<String, String> = emptyMap(),
    private val logSink: AiLogSink? = null,
    private val debug: Boolean = false,
) : AiProvider {
    private val endpoint = object : ProtocolEndpoint {
        override fun url(modelName: String, stream: Boolean) = baseUrl.trimEnd('/') + "/v1/messages"
        override fun headers(stream: Boolean) = buildMap {
            put("x-api-key", apiKey)
            put("anthropic-version", version)
            if (beta.isNotEmpty()) put("anthropic-beta", beta.joinToString(","))
            if (stream) put("Accept", "text/event-stream")
            putAll(defaultHeaders)
        }
    }

    override fun languageModel(modelId: String): AiLanguageModel =
        ProtocolTextModel(id, modelId, WireFormat.ANTHROPIC, httpClient, endpoint, ProviderProfiles.CONSERVATIVE, logSink, debug)
}
