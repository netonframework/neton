package neton.ai.adapter.openaicompatible

import neton.ai.AiLogSink
import neton.ai.adapter.ProtocolEndpoint
import neton.ai.adapter.ProtocolTextModel
import neton.ai.protocol.ir.WireFormat
import neton.ai.protocol.profile.ProviderProfile
import neton.ai.protocol.profile.ProviderProfiles
import neton.ai.provider.AiEmbeddingModel
import neton.ai.provider.AiProvider
import neton.ai.provider.AiStreamingTextModel
import neton.ai.provider.AiTextModel
import neton.http.client.HttpClient

/** OpenAI 兼容的 API 面：Chat Completions（绝大多数兼容服务）或 Responses（OpenAI、Azure v1 等）。 */
enum class OpenAiApi(val format: WireFormat, val path: String) {
    Chat(WireFormat.OPENAI_CHAT, "/chat/completions"),
    Responses(WireFormat.OPENAI_RESPONSES, "/responses"),
}

/**
 * OpenAI 及兼容服务。厂商差异由 [profile] 描述（spec §9）：未指定时按 baseUrl 选内置配置，
 * [streamUsage] 非 null 时覆盖配置里的同名项。
 */
class OpenAiCompatibleProvider(
    override val id: String,
    private val httpClient: HttpClient,
    private val baseUrl: String,
    private val apiKey: String,
    private val organization: String? = null,
    private val defaultHeaders: Map<String, String> = emptyMap(),
    private val logSink: AiLogSink? = null,
    private val debug: Boolean = false,
    streamUsage: Boolean? = null,
    profile: ProviderProfile? = null,
    private val api: OpenAiApi = OpenAiApi.Chat,
) : AiProvider {
    private val profile: ProviderProfile = (profile ?: ProviderProfiles.forBaseUrl(baseUrl))
        .let { p -> streamUsage?.let { p.copy(streamUsage = it) } ?: p }

    private val endpoint = object : ProtocolEndpoint {
        override fun url(modelName: String, stream: Boolean) = baseUrl.trimEnd('/') + api.path
        override fun headers(stream: Boolean) = buildMap {
            put("Authorization", "Bearer $apiKey")
            organization?.let { put("OpenAI-Organization", it) }
            if (stream) put("Accept", "text/event-stream")
            putAll(defaultHeaders)
        }
    }

    override fun textModel(modelName: String): AiTextModel = streamingTextModel(modelName)

    override fun streamingTextModel(modelName: String): AiStreamingTextModel =
        ProtocolTextModel(id, modelName, api.format, httpClient, endpoint, profile, logSink, debug)

    override fun embeddingModel(modelName: String): AiEmbeddingModel = OpenAiCompatibleEmbeddingModel(
        providerId = id, modelName = modelName, httpClient = httpClient,
        baseUrl = baseUrl, apiKey = apiKey, organization = organization,
        defaultHeaders = defaultHeaders, logSink = logSink, debug = debug,
    )
}
