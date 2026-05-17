// neton-ai/src/commonMain/kotlin/neton/ai/adapter/openaicompatible/OpenAiCompatibleResponseMapper.kt
package neton.ai.adapter.openaicompatible

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import neton.ai.AiContent
import neton.ai.AiError
import neton.ai.AiException
import neton.ai.AiFinishReason
import neton.ai.AiMessage
import neton.ai.AiRole
import neton.ai.AiToolCall
import neton.ai.AiUsage
import neton.ai.adapter.openaicompatible.dto.OpenAiChatResponse
import neton.ai.adapter.openaicompatible.dto.OpenAiErrorEnvelope
import neton.ai.provider.ProviderCallResponse

internal class OpenAiCompatibleResponseMapper(
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
) {
    fun fromWireBody(body: String): ProviderCallResponse {
        val resp = try {
            json.decodeFromString(OpenAiChatResponse.serializer(), body)
        } catch (e: SerializationException) {
            throw AiException(AiError.Unknown("Invalid OpenAI response JSON: ${e.message}", e))
        }
        val choice = resp.choices.firstOrNull()
            ?: throw AiException(AiError.Unknown("OpenAI response has no choices", null))
        val text = choice.message.content.orEmpty()
        val toolCalls = choice.message.toolCalls.orEmpty().map { tc ->
            AiToolCall(id = tc.id, name = tc.function.name, argumentsJson = tc.function.arguments)
        }
        return ProviderCallResponse(
            message = AiMessage(
                role = AiRole.Assistant,
                content = if (text.isNotEmpty()) listOf(AiContent.Text(text)) else emptyList(),
                toolCalls = toolCalls,
            ),
            text = text,
            toolCalls = toolCalls,
            usage = resp.usage?.let { AiUsage(it.promptTokens, it.completionTokens, it.totalTokens) },
            finishReason = mapFinishReason(choice.finishReason),
        )
    }

    fun errorFromStatus(statusCode: Int, body: String): Nothing {
        val parsedMessage = tryParseErrorMessage(body) ?: body.take(500)
        val parsedCode = tryParseErrorCode(body)
        throw AiException(when (statusCode) {
            401 -> AiError.Unauthorized(parsedMessage)
            403 -> AiError.Forbidden(parsedMessage)
            429 -> AiError.RateLimited(retryAfterMillis = null, message = parsedMessage)
            in 500..599 -> AiError.ServerError(statusCode, parsedMessage)
            400 -> when (parsedCode) {
                "context_length_exceeded" -> AiError.ContextLengthExceeded(parsedMessage)
                else -> AiError.InvalidRequest(parsedMessage)
            }
            404 -> when (parsedCode) {
                "model_not_found" -> AiError.ModelNotFound("unknown", parsedMessage)
                else -> AiError.InvalidRequest(parsedMessage)
            }
            else -> AiError.Unknown("HTTP $statusCode: $parsedMessage", null)
        })
    }

    private fun mapFinishReason(s: String?): AiFinishReason = when (s) {
        "stop" -> AiFinishReason.Stop
        "length" -> AiFinishReason.Length
        "tool_calls", "function_call" -> AiFinishReason.ToolCalls
        "content_filter" -> AiFinishReason.ContentFilter
        else -> AiFinishReason.Other
    }

    private fun tryParseErrorMessage(body: String): String? = try {
        json.decodeFromString(OpenAiErrorEnvelope.serializer(), body).error.message
    } catch (_: Throwable) { null }

    private fun tryParseErrorCode(body: String): String? = try {
        json.decodeFromString(OpenAiErrorEnvelope.serializer(), body).error.code
    } catch (_: Throwable) { null }
}
