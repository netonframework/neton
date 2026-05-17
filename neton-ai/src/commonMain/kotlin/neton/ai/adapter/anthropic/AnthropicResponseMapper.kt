// neton-ai/src/commonMain/kotlin/neton/ai/adapter/anthropic/AnthropicResponseMapper.kt
package neton.ai.adapter.anthropic

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import neton.ai.AiContent
import neton.ai.AiError
import neton.ai.AiException
import neton.ai.AiFinishReason
import neton.ai.AiMessage
import neton.ai.AiRole
import neton.ai.AiToolCall
import neton.ai.AiUsage
import neton.ai.adapter.anthropic.dto.AnthropicContentBlock
import neton.ai.adapter.anthropic.dto.AnthropicErrorEnvelope
import neton.ai.adapter.anthropic.dto.AnthropicMessagesResponse
import neton.ai.provider.ProviderCallResponse

internal class AnthropicResponseMapper(
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
) {
    fun fromWireBody(body: String): ProviderCallResponse {
        val resp = try {
            json.decodeFromString(AnthropicMessagesResponse.serializer(), body)
        } catch (e: SerializationException) {
            throw AiException(AiError.Unknown("Invalid Anthropic response JSON: ${e.message}", e))
        }
        val texts = resp.content.filterIsInstance<AnthropicContentBlock.Text>().map { it.text }
        val toolUses = resp.content.filterIsInstance<AnthropicContentBlock.ToolUse>()
        val text = texts.joinToString("\n")
        val toolCalls = toolUses.map { tu ->
            AiToolCall(
                id = tu.id,
                name = tu.name,
                argumentsJson = json.encodeToString(JsonElement.serializer(), tu.input),
            )
        }
        return ProviderCallResponse(
            message = AiMessage(
                role = AiRole.Assistant,
                content = if (text.isNotEmpty()) listOf(AiContent.Text(text)) else emptyList(),
                toolCalls = toolCalls,
            ),
            text = text,
            toolCalls = toolCalls,
            usage = resp.usage?.let { AiUsage(inputTokens = it.inputTokens, outputTokens = it.outputTokens, totalTokens = null) },
            finishReason = mapStopReason(resp.stopReason),
        )
    }

    fun errorFromStatus(statusCode: Int, body: String): Nothing {
        val parsed = tryParseError(body)
        val msg = parsed?.message ?: body.take(500)
        val type = parsed?.type
        throw AiException(when (statusCode) {
            401 -> AiError.Unauthorized(msg)
            403 -> AiError.Forbidden(msg)
            429 -> AiError.RateLimited(retryAfterMillis = null, message = msg)
            in 500..599 -> AiError.ServerError(statusCode, msg)
            400 -> when {
                type == "invalid_request_error" && msg.contains("context", ignoreCase = true) ->
                    AiError.ContextLengthExceeded(msg)
                else -> AiError.InvalidRequest(msg)
            }
            404 -> AiError.ModelNotFound("unknown", msg)
            else -> AiError.Unknown("HTTP $statusCode: $msg", null)
        })
    }

    private fun mapStopReason(s: String?): AiFinishReason = when (s) {
        "end_turn" -> AiFinishReason.Stop
        "max_tokens" -> AiFinishReason.Length
        "tool_use" -> AiFinishReason.ToolCalls
        "stop_sequence" -> AiFinishReason.Stop
        else -> AiFinishReason.Other
    }

    private fun tryParseError(body: String) = try {
        json.decodeFromString(AnthropicErrorEnvelope.serializer(), body).error
    } catch (_: Throwable) { null }
}
