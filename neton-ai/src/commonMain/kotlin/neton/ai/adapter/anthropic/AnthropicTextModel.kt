// neton-ai/src/commonMain/kotlin/neton/ai/adapter/anthropic/AnthropicTextModel.kt
package neton.ai.adapter.anthropic

import neton.core.http.HttpHeaders

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.json.Json
import neton.ai.AiError
import neton.ai.AiException
import neton.ai.AiLogSink
import neton.ai.AiStreamEvent
import neton.ai.adapter.anthropic.dto.AnthropicMessagesRequest
import neton.ai.internal.withRedactedValues
import neton.ai.provider.AiStreamingTextModel
import neton.ai.provider.ProviderCallRequest
import neton.ai.provider.ProviderCallResponse
import neton.http.client.NetonHttpBody
import neton.http.client.NetonHttpClient
import neton.http.client.NetonHttpError
import neton.http.client.NetonHttpException
import neton.http.client.NetonHttpMethod
import neton.http.client.NetonHttpRequest

internal class AnthropicTextModel(
    override val providerId: String,
    override val modelName: String,
    private val httpClient: NetonHttpClient,
    private val baseUrl: String,
    private val apiKey: String,
    private val version: String,
    private val beta: List<String>,
    private val defaultHeaders: Map<String, String>,
    private val logSink: AiLogSink? = null,
    private val debug: Boolean = false,
    private val requestMapper: AnthropicRequestMapper = AnthropicRequestMapper(),
    private val responseMapper: AnthropicResponseMapper = AnthropicResponseMapper(),
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false },
    private val streamMapper: AnthropicStreamMapper = AnthropicStreamMapper(),
) : AiStreamingTextModel {

    private fun buildHeaders(): Map<String, String> = buildMap {
        put("x-api-key", apiKey)
        put("anthropic-version", version)
        if (beta.isNotEmpty()) put("anthropic-beta", beta.joinToString(","))
        putAll(defaultHeaders)
    }

    override suspend fun generate(request: ProviderCallRequest): ProviderCallResponse {
        val wire = requestMapper.toWire(modelName, request)
        val bodyJson = json.encodeToString(AnthropicMessagesRequest.serializer(), wire)
        val url = "$baseUrl/v1/messages"
        val headers = buildHeaders()
        if (debug && logSink != null) {
            logSink.invoke("ai.provider.$providerId model=$modelName POST $url headers=${headers.withRedactedValues()}")
        }
        val resp = try {
            httpClient.request(NetonHttpRequest(
                method = NetonHttpMethod.Post,
                url = url,
                headers = HttpHeaders.from(headers),
                body = NetonHttpBody.Json(bodyJson),
                metadata = request.metadata,
            ))
        } catch (e: NetonHttpException) {
            throw AiException(when (val err = e.error) {
                is neton.http.client.NetonHttpError.Network -> AiError.Network(err.message, err.cause)
                is neton.http.client.NetonHttpError.Timeout -> AiError.Timeout(err.message, err.cause)
                is neton.http.client.NetonHttpError.Http -> throw IllegalStateException("Http error path unused")
                is neton.http.client.NetonHttpError.Unknown -> AiError.Unknown(err.message, err.cause)
            })
        }
        if (resp.statusCode !in 200..299) {
            responseMapper.errorFromStatus(resp.statusCode, resp.body)
        }
        return responseMapper.fromWireBody(resp.body)
    }

    override fun stream(request: ProviderCallRequest): Flow<AiStreamEvent> = channelFlow {
        val wire = requestMapper.toWire(modelName, request).copy(stream = true)
        val bodyJson = json.encodeToString(AnthropicMessagesRequest.serializer(), wire)
        val url = "$baseUrl/v1/messages"
        val headers = buildHeaders() + mapOf("Accept" to "text/event-stream")

        if (debug && logSink != null) {
            logSink.invoke("ai.provider.$providerId model=$modelName POST $url (stream) headers=${headers.withRedactedValues()}")
        }

        val chunkFlow = httpClient.stream(NetonHttpRequest(
            method = NetonHttpMethod.Post,
            url = url,
            headers = HttpHeaders.from(headers),
            body = NetonHttpBody.Json(bodyJson),
            metadata = request.metadata,
        ))

        try {
            streamMapper.map(chunkFlow).collect { send(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: NetonHttpException) {
            throw AiException(when (val err = e.error) {
                is NetonHttpError.Network -> AiError.Network(err.message, err.cause)
                is NetonHttpError.Timeout -> AiError.Timeout(err.message, err.cause)
                is NetonHttpError.Http -> mapHttpStreamError(err)
                is NetonHttpError.Unknown -> AiError.Unknown(err.message, err.cause)
            })
        } catch (e: AiException) {
            throw e
        } catch (e: Throwable) {
            throw AiException(AiError.Unknown(e.message ?: "stream error", e))
        }
    }

    private fun mapHttpStreamError(err: NetonHttpError.Http): AiError {
        val body = err.body
        if (body != null) {
            // Reuse the full non-streaming error mapping (parses Anthropic error.type / error.message).
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
