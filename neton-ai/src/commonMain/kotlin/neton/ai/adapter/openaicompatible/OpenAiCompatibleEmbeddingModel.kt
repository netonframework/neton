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
import neton.http.client.HttpClientBody
import neton.http.client.HttpClient
import neton.http.client.HttpClientException
import neton.http.client.HttpClientMethod
import neton.http.client.HttpClientRequest

internal class OpenAiCompatibleEmbeddingModel(
    override val providerId: String,
    override val modelName: String,
    private val httpClient: HttpClient,
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
            httpClient.request(HttpClientRequest(
                method = HttpClientMethod.Post,
                url = "$baseUrl/embeddings",
                headers = HttpHeaders.from(headers),
                body = HttpClientBody.Json(bodyJson),
                metadata = request.metadata,
            ))
        } catch (e: HttpClientException) {
            throw AiException(when (val err = e.error) {
                is neton.http.client.HttpClientError.Network -> AiError.Network(err.message, err.cause)
                is neton.http.client.HttpClientError.Timeout -> AiError.Timeout(err.message, err.cause)
                is neton.http.client.HttpClientError.Http -> AiError.Unknown("HTTP error: ${err.message}", null)
                is neton.http.client.HttpClientError.Unknown -> AiError.Unknown(err.message, err.cause)
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
