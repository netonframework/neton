// neton-ai/src/commonMain/kotlin/neton/ai/adapter/openaicompatible/OpenAiCompatibleTextModel.kt
package neton.ai.adapter.openaicompatible

import neton.core.http.HttpHeaders

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.json.Json
import neton.ai.AiError
import neton.ai.AiException
import neton.ai.AiLogSink
import neton.ai.AiStreamEvent
import neton.ai.adapter.openaicompatible.dto.OpenAiChatRequest
import neton.ai.internal.withRedactedValues
import neton.ai.provider.AiStreamingTextModel
import neton.ai.provider.ProviderCallRequest
import neton.ai.provider.ProviderCallResponse
import neton.http.client.HttpClientBody
import neton.http.client.HttpClient
import neton.http.client.HttpClientError
import neton.http.client.HttpClientException
import neton.http.client.HttpClientMethod
import neton.http.client.HttpClientRequest

internal class OpenAiCompatibleTextModel(
    override val providerId: String,
    override val modelName: String,
    private val httpClient: HttpClient,
    private val baseUrl: String,
    private val apiKey: String,
    private val organization: String?,
    private val defaultHeaders: Map<String, String>,
    private val logSink: AiLogSink? = null,
    private val debug: Boolean = false,
    private val requestMapper: OpenAiCompatibleRequestMapper = OpenAiCompatibleRequestMapper(),
    private val responseMapper: OpenAiCompatibleResponseMapper = OpenAiCompatibleResponseMapper(),
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false },
    private val streamMapper: OpenAiCompatibleStreamMapper = OpenAiCompatibleStreamMapper(),
) : AiStreamingTextModel {
    override suspend fun generate(request: ProviderCallRequest): ProviderCallResponse {
        val wire = requestMapper.toWire(modelName, request)
        val bodyJson = json.encodeToString(OpenAiChatRequest.serializer(), wire)
        val url = "$baseUrl/chat/completions"
        val headers = buildMap {
            put("Authorization", "Bearer $apiKey")
            organization?.let { put("OpenAI-Organization", it) }
            putAll(defaultHeaders)
        }
        if (debug && logSink != null) {
            logSink.invoke("ai.provider.$providerId model=$modelName POST $url headers=${headers.withRedactedValues()}")
        }
        val resp = try {
            httpClient.request(HttpClientRequest(
                method = HttpClientMethod.Post,
                url = url,
                headers = HttpHeaders.from(headers),
                body = HttpClientBody.Json(bodyJson),
                metadata = request.metadata,
            ))
        } catch (e: HttpClientException) {
            throw AiException(when (val err = e.error) {
                is neton.http.client.HttpClientError.Network -> AiError.Network(err.message, err.cause)
                is neton.http.client.HttpClientError.Timeout -> AiError.Timeout(err.message, err.cause)
                is neton.http.client.HttpClientError.Http -> throw IllegalStateException("Http error should not occur here (expectSuccess=false)")
                is neton.http.client.HttpClientError.Unknown -> AiError.Unknown(err.message, err.cause)
            })
        }
        if (resp.statusCode !in 200..299) {
            responseMapper.errorFromStatus(resp.statusCode, resp.body)
        }
        return responseMapper.fromWireBody(resp.body)
    }

    override fun stream(request: ProviderCallRequest): Flow<AiStreamEvent> = channelFlow {
        val wire = requestMapper.toWire(modelName, request).copy(stream = true)
        val bodyJson = json.encodeToString(OpenAiChatRequest.serializer(), wire)
        val headers = buildMap {
            put("Authorization", "Bearer $apiKey")
            organization?.let { put("OpenAI-Organization", it) }
            put("Accept", "text/event-stream")
            putAll(defaultHeaders)
        }

        if (debug && logSink != null) {
            logSink.invoke("ai.provider.$providerId model=$modelName POST $baseUrl/chat/completions (stream) headers=${headers.withRedactedValues()}")
        }

        val chunkFlow = httpClient.stream(HttpClientRequest(
            method = HttpClientMethod.Post,
            url = "$baseUrl/chat/completions",
            headers = HttpHeaders.from(headers),
            body = HttpClientBody.Json(bodyJson),
            metadata = request.metadata,
        ))

        try {
            streamMapper.map(chunkFlow).collect { send(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpClientException) {
            throw AiException(when (val err = e.error) {
                is HttpClientError.Network -> AiError.Network(err.message, err.cause)
                is HttpClientError.Timeout -> AiError.Timeout(err.message, err.cause)
                is HttpClientError.Http -> mapHttpStreamError(err)
                is HttpClientError.Unknown -> AiError.Unknown(err.message, err.cause)
            })
        } catch (e: AiException) {
            throw e
        } catch (e: Throwable) {
            throw AiException(AiError.Unknown(e.message ?: "stream error", e))
        }
    }

    private fun mapHttpStreamError(err: HttpClientError.Http): AiError {
        val body = err.body
        if (body != null) {
            // Reuse the full non-streaming error mapping (parses error.message / error.code,
            // including context_length_exceeded and model_not_found).
            return try {
                responseMapper.errorFromStatus(err.statusCode, body)
            } catch (e: AiException) {
                e.error
            }
        }
        return when (err.statusCode) {
            401 -> AiError.Unauthorized(err.message)
            403 -> AiError.Forbidden(err.message)
            429 -> AiError.RateLimited(retryAfterMillis = null, message = err.message)
            in 500..599 -> AiError.ServerError(err.statusCode, err.message)
            else -> AiError.Unknown("HTTP error in stream: ${err.message}", null)
        }
    }
}
