// neton-ai/src/commonMain/kotlin/neton/ai/adapter/openaicompatible/OpenAiCompatibleTextModel.kt
package neton.ai.adapter.openaicompatible

import kotlinx.serialization.json.Json
import neton.ai.AiError
import neton.ai.AiException
import neton.ai.adapter.openaicompatible.dto.OpenAiChatRequest
import neton.ai.provider.AiTextModel
import neton.ai.provider.ProviderCallRequest
import neton.ai.provider.ProviderCallResponse
import neton.http.client.NetonHttpBody
import neton.http.client.NetonHttpClient
import neton.http.client.NetonHttpException
import neton.http.client.NetonHttpMethod
import neton.http.client.NetonHttpRequest

internal class OpenAiCompatibleTextModel(
    override val providerId: String,
    override val modelName: String,
    private val httpClient: NetonHttpClient,
    private val baseUrl: String,
    private val apiKey: String,
    private val organization: String?,
    private val defaultHeaders: Map<String, String>,
    private val requestMapper: OpenAiCompatibleRequestMapper = OpenAiCompatibleRequestMapper(),
    private val responseMapper: OpenAiCompatibleResponseMapper = OpenAiCompatibleResponseMapper(),
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false },
) : AiTextModel {
    override suspend fun generate(request: ProviderCallRequest): ProviderCallResponse {
        val wire = requestMapper.toWire(modelName, request)
        val bodyJson = json.encodeToString(OpenAiChatRequest.serializer(), wire)
        val headers = buildMap {
            put("Authorization", "Bearer $apiKey")
            organization?.let { put("OpenAI-Organization", it) }
            putAll(defaultHeaders)
        }
        val resp = try {
            httpClient.request(NetonHttpRequest(
                method = NetonHttpMethod.Post,
                url = "$baseUrl/chat/completions",
                headers = headers,
                body = NetonHttpBody.Json(bodyJson),
                metadata = request.metadata,
            ))
        } catch (e: NetonHttpException) {
            throw AiException(when (val err = e.error) {
                is neton.http.client.NetonHttpError.Network -> AiError.Network(err.message, err.cause)
                is neton.http.client.NetonHttpError.Timeout -> AiError.Timeout(err.message, err.cause)
                is neton.http.client.NetonHttpError.Http -> throw IllegalStateException("Http error should not occur here (expectSuccess=false)")
                is neton.http.client.NetonHttpError.Unknown -> AiError.Unknown(err.message, err.cause)
            })
        }
        if (resp.statusCode !in 200..299) {
            responseMapper.errorFromStatus(resp.statusCode, resp.body)
        }
        return responseMapper.fromWireBody(resp.body)
    }
}
