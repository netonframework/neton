package neton.ai.adapter.openaicompatible

import neton.core.http.HttpHeaders

import kotlinx.serialization.json.Json
import neton.ai.AiError
import neton.ai.AiException
import neton.ai.AiUsage
import neton.ai.adapter.openaicompatible.dto.OpenAiEmbeddingRequest
import neton.ai.adapter.openaicompatible.dto.OpenAiEmbeddingResponse
import neton.ai.internal.withRedactedValues
import neton.ai.provider.AiEmbeddingModel
import neton.ai.provider.ProviderEmbedRequest
import neton.ai.provider.ProviderEmbedResponse
import neton.http.client.NetonHttpBody
import neton.http.client.NetonHttpClient
import neton.http.client.NetonHttpException
import neton.http.client.NetonHttpMethod
import neton.http.client.NetonHttpRequest

internal class OpenAiCompatibleEmbeddingModel(
    override val providerId: String,
    override val modelName: String,
    private val httpClient: NetonHttpClient,
    private val baseUrl: String,
    private val apiKey: String,
    private val organization: String?,
    private val defaultHeaders: Map<String, String>,
    private val logSink: ((String) -> Unit)? = null,
    private val debug: Boolean = false,
    private val responseMapper: OpenAiCompatibleResponseMapper = OpenAiCompatibleResponseMapper(),
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false },
) : AiEmbeddingModel {

    override suspend fun embed(request: ProviderEmbedRequest): ProviderEmbedResponse {
        val wire = OpenAiEmbeddingRequest(model = modelName, input = request.input)
        val bodyJson = json.encodeToString(OpenAiEmbeddingRequest.serializer(), wire)
        val headers = buildMap {
            put("Authorization", "Bearer $apiKey")
            organization?.let { put("OpenAI-Organization", it) }
            putAll(defaultHeaders)
        }
        if (debug && logSink != null) {
            logSink.invoke("ai.provider.${providerId} model=$modelName POST $baseUrl/embeddings headers=${headers.withRedactedValues()}")
        }
        val resp = try {
            httpClient.request(NetonHttpRequest(
                method = NetonHttpMethod.Post,
                url = "$baseUrl/embeddings",
                headers = HttpHeaders.from(headers),
                body = NetonHttpBody.Json(bodyJson),
                metadata = request.metadata,
            ))
        } catch (e: NetonHttpException) {
            throw AiException(when (val err = e.error) {
                is neton.http.client.NetonHttpError.Network -> AiError.Network(err.message, err.cause)
                is neton.http.client.NetonHttpError.Timeout -> AiError.Timeout(err.message, err.cause)
                is neton.http.client.NetonHttpError.Http -> AiError.Unknown("HTTP error: ${err.message}", null)
                is neton.http.client.NetonHttpError.Unknown -> AiError.Unknown(err.message, err.cause)
            })
        }
        if (resp.statusCode !in 200..299) {
            responseMapper.errorFromStatus(resp.statusCode, resp.body)
        }
        val parsed = json.decodeFromString(OpenAiEmbeddingResponse.serializer(), resp.body)
        // Sort by index to preserve input order
        val embeddings = parsed.data.sortedBy { it.index }.map { it.embedding.toFloatArray() }
        return ProviderEmbedResponse(
            embeddings = embeddings,
            usage = parsed.usage?.let { AiUsage(it.promptTokens, it.completionTokens, it.totalTokens) },
        )
    }
}
