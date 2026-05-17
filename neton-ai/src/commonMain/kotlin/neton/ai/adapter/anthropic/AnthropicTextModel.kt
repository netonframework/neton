// neton-ai/src/commonMain/kotlin/neton/ai/adapter/anthropic/AnthropicTextModel.kt
package neton.ai.adapter.anthropic

import kotlinx.serialization.json.Json
import neton.ai.AiError
import neton.ai.AiException
import neton.ai.AiLogSink
import neton.ai.adapter.anthropic.dto.AnthropicMessagesRequest
import neton.ai.internal.withRedactedValues
import neton.ai.provider.AiTextModel
import neton.ai.provider.ProviderCallRequest
import neton.ai.provider.ProviderCallResponse
import neton.http.client.NetonHttpBody
import neton.http.client.NetonHttpClient
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
) : AiTextModel {
    override suspend fun generate(request: ProviderCallRequest): ProviderCallResponse {
        val wire = requestMapper.toWire(modelName, request)
        val bodyJson = json.encodeToString(AnthropicMessagesRequest.serializer(), wire)
        val url = "$baseUrl/v1/messages"
        val headers = buildMap {
            put("x-api-key", apiKey)
            put("anthropic-version", version)
            if (beta.isNotEmpty()) put("anthropic-beta", beta.joinToString(","))
            putAll(defaultHeaders)
        }
        if (debug && logSink != null) {
            logSink.invoke("ai.provider.$providerId model=$modelName POST $url headers=${headers.withRedactedValues()}")
        }
        val resp = try {
            httpClient.request(NetonHttpRequest(
                method = NetonHttpMethod.Post,
                url = url,
                headers = headers,
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
}
