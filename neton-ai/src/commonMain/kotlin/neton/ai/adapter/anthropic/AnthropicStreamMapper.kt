package neton.ai.adapter.anthropic

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import neton.ai.AiContent
import neton.ai.AiError
import neton.ai.AiFinishReason
import neton.ai.AiMessage
import neton.ai.AiRole
import neton.ai.AiStreamEvent
import neton.ai.AiToolCall
import neton.ai.AiUsage
import neton.ai.adapter.anthropic.dto.AnthropicContentBlock
import neton.ai.adapter.anthropic.dto.AnthropicContentBlockDeltaData
import neton.ai.adapter.anthropic.dto.AnthropicContentBlockStartData
import neton.ai.adapter.anthropic.dto.AnthropicContentBlockStopData
import neton.ai.adapter.anthropic.dto.AnthropicMessageDeltaData
import neton.ai.adapter.anthropic.dto.AnthropicMessageStartData
import neton.ai.adapter.anthropic.dto.AnthropicStreamErrorData
import neton.http.client.NetonHttpStreamChunk
import neton.http.client.sse.parseSseEvents

// Per-block-index accumulator (Kotlin disallows local sealed interfaces; keep at file scope)
private sealed interface BlockAcc
private data class TextAcc(val builder: StringBuilder = StringBuilder()) : BlockAcc
private data class ToolUseAcc(val id: String, val name: String, val inputBuffer: StringBuilder = StringBuilder()) : BlockAcc

internal class AnthropicStreamMapper(
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
) {

    fun map(chunks: Flow<NetonHttpStreamChunk>): Flow<AiStreamEvent> = flow {
        val blocks = mutableMapOf<Int, BlockAcc>()
        val orderedBlockIndexes = mutableListOf<Int>()  // preserve insertion order for final assembly
        var lastUsage: AiUsage? = null  // input from message_start; updated cumulatively from message_delta
        var finishReason: AiFinishReason = AiFinishReason.Other
        var failed = false

        chunks.parseSseEvents().collect { sseEvent ->
            if (failed) return@collect
            val eventType = sseEvent.event ?: return@collect  // anonymous events: ignore
            val data = sseEvent.data
            try {
                when (eventType) {
                    "message_start" -> {
                        val parsed = json.decodeFromString(AnthropicMessageStartData.serializer(), data)
                        parsed.message.usage?.let {
                            lastUsage = AiUsage(inputTokens = it.inputTokens, outputTokens = it.outputTokens, totalTokens = null)
                        }
                    }
                    "content_block_start" -> {
                        val parsed = json.decodeFromString(AnthropicContentBlockStartData.serializer(), data)
                        when (val block = parsed.contentBlock) {
                            is AnthropicContentBlock.Text -> {
                                blocks[parsed.index] = TextAcc()
                                orderedBlockIndexes += parsed.index
                            }
                            is AnthropicContentBlock.ToolUse -> {
                                blocks[parsed.index] = ToolUseAcc(id = block.id, name = block.name)
                                orderedBlockIndexes += parsed.index
                                emit(AiStreamEvent.ToolCallStarted(id = block.id, name = block.name))
                            }
                            is AnthropicContentBlock.ToolResult -> {
                                // tool_result blocks come from user messages, not from assistant streams; ignore
                            }
                        }
                    }
                    "content_block_delta" -> {
                        val parsed = json.decodeFromString(AnthropicContentBlockDeltaData.serializer(), data)
                        val acc = blocks[parsed.index] ?: return@collect
                        when (parsed.delta.type) {
                            "text_delta" -> {
                                val txt = parsed.delta.text.orEmpty()
                                if (txt.isNotEmpty() && acc is TextAcc) {
                                    acc.builder.append(txt)
                                    emit(AiStreamEvent.TextDelta(txt))
                                }
                            }
                            "input_json_delta" -> {
                                val frag = parsed.delta.partialJson.orEmpty()
                                if (acc is ToolUseAcc) {
                                    acc.inputBuffer.append(frag)
                                    emit(AiStreamEvent.ToolCallArgumentsDelta(id = acc.id, argumentsFragment = frag))
                                }
                            }
                            // Unknown delta types: ignore (forward-compatible per spec §4.2)
                        }
                    }
                    "content_block_stop" -> {
                        val parsed = json.decodeFromString(AnthropicContentBlockStopData.serializer(), data)
                        val acc = blocks[parsed.index] ?: return@collect
                        if (acc is ToolUseAcc) {
                            // Parse accumulated partial_json into final argumentsJson
                            val argsJson = acc.inputBuffer.toString().ifBlank { "{}" }
                            // Validate JSON parses (spec §4.2 wants explicit failure on invalid)
                            try {
                                json.parseToJsonElement(argsJson)
                            } catch (e: SerializationException) {
                                emit(AiStreamEvent.Failed(AiError.Unknown("Invalid tool input JSON from Anthropic: ${e.message}", e)))
                                failed = true
                                return@collect
                            }
                            emit(AiStreamEvent.ToolCallReady(AiToolCall(id = acc.id, name = acc.name, argumentsJson = argsJson)))
                        }
                        // text block stop: no event
                    }
                    "message_delta" -> {
                        val parsed = json.decodeFromString(AnthropicMessageDeltaData.serializer(), data)
                        parsed.delta.stopReason?.let { finishReason = mapStopReason(it) }
                        // usage is CUMULATIVE — REPLACE (not increment), preserving input_tokens from message_start if not present
                        parsed.usage?.let {
                            lastUsage = AiUsage(
                                inputTokens = it.inputTokens ?: lastUsage?.inputTokens,
                                outputTokens = it.outputTokens ?: lastUsage?.outputTokens,
                                totalTokens = null,
                            )
                        }
                    }
                    "message_stop" -> {
                        // Will emit Completed after collect completes
                    }
                    "ping" -> { /* ignore */ }
                    "error" -> {
                        val parsed = json.decodeFromString(AnthropicStreamErrorData.serializer(), data)
                        val err = mapErrorType(parsed.error.type, parsed.error.message)
                        emit(AiStreamEvent.Failed(err))
                        failed = true
                        return@collect
                    }
                    else -> {
                        // Unknown event type: ignore (do not fail; per spec §6 gate 14)
                    }
                }
            } catch (e: SerializationException) {
                emit(AiStreamEvent.Failed(AiError.Unknown("Invalid Anthropic event payload (event=$eventType): ${e.message}", e)))
                failed = true
            }
        }

        if (failed) return@flow

        // Assemble final message: text from all TextAcc + toolCalls from all ToolUseAcc, in insertion order
        val textParts = mutableListOf<String>()
        val toolCalls = mutableListOf<AiToolCall>()
        for (idx in orderedBlockIndexes) {
            when (val a = blocks[idx]) {
                is TextAcc -> if (a.builder.isNotEmpty()) textParts += a.builder.toString()
                is ToolUseAcc -> {
                    val argsJson = a.inputBuffer.toString().ifBlank { "{}" }
                    toolCalls += AiToolCall(a.id, a.name, argsJson)
                }
                null -> {}
            }
        }
        val text = textParts.joinToString("\n")
        emit(AiStreamEvent.Completed(
            message = AiMessage(
                role = AiRole.Assistant,
                content = if (text.isNotEmpty()) listOf(AiContent.Text(text)) else emptyList(),
                toolCalls = toolCalls,
            ),
            text = text,
            usage = lastUsage,
            finishReason = finishReason,
        ))
    }

    private fun mapStopReason(s: String): AiFinishReason = when (s) {
        "end_turn" -> AiFinishReason.Stop
        "max_tokens" -> AiFinishReason.Length
        "tool_use" -> AiFinishReason.ToolCalls
        "stop_sequence" -> AiFinishReason.Stop
        else -> AiFinishReason.Other
    }

    /** Map Anthropic error.type strings to AiError. */
    private fun mapErrorType(type: String, msg: String): AiError = when (type) {
        "authentication_error" -> AiError.Unauthorized(msg)
        "permission_error" -> AiError.Forbidden(msg)
        "rate_limit_error" -> AiError.RateLimited(retryAfterMillis = null, message = msg)
        "overloaded_error" -> AiError.ServerError(503, msg)
        "api_error" -> AiError.ServerError(500, msg)
        "invalid_request_error" -> AiError.InvalidRequest(msg)
        "not_found_error" -> AiError.ModelNotFound("unknown", msg)
        else -> AiError.Unknown("Anthropic error type=$type: $msg", null)
    }
}
