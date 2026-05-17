package neton.ai.adapter.openaicompatible

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
import neton.ai.adapter.openaicompatible.dto.OpenAiStreamChunk
import neton.http.client.NetonHttpStreamChunk
import neton.http.client.sse.parseSseEvents

private class ToolCallAccumulator {
    var id: String? = null
    var name: String? = null
    val arguments: StringBuilder = StringBuilder()
    var startedEmitted: Boolean = false
}

internal class OpenAiCompatibleStreamMapper(
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
) {

    /** Map raw chunked HTTP body to a stream of AiStreamEvent. Does NOT emit Started/ToolResultReady. */
    fun map(chunks: Flow<NetonHttpStreamChunk>): Flow<AiStreamEvent> = flow {
        // Per-tool-call accumulator (keyed by OpenAI 'index' field)
        val acc = mutableMapOf<Int, ToolCallAccumulator>()

        val accumulatedText = StringBuilder()
        var finishReason: AiFinishReason = AiFinishReason.Other
        var usage: AiUsage? = null
        var failed = false

        chunks.parseSseEvents().collect { event ->
            if (failed) return@collect
            val data = event.data
            // OpenAI ends stream with "data: [DONE]"
            if (data == "[DONE]") return@collect

            val chunk = try {
                json.decodeFromString(OpenAiStreamChunk.serializer(), data)
            } catch (e: SerializationException) {
                // Bad chunk: surface as Failed and end
                emit(AiStreamEvent.Failed(AiError.Unknown("Invalid OpenAI stream chunk JSON: ${e.message}", e)))
                failed = true
                return@collect
            }

            // Process choices[0] (single-choice assumption matches non-streaming behavior)
            val choice = chunk.choices.firstOrNull() ?: return@collect
            val delta = choice.delta

            delta.content?.let {
                if (it.isNotEmpty()) {
                    accumulatedText.append(it)
                    emit(AiStreamEvent.TextDelta(it))
                }
            }

            delta.toolCalls?.forEach { tcDelta ->
                val a = acc.getOrPut(tcDelta.index) { ToolCallAccumulator() }
                if (tcDelta.id != null) a.id = tcDelta.id
                tcDelta.function?.name?.let { a.name = it }
                val aId = a.id
                if (!a.startedEmitted && aId != null) {
                    emit(AiStreamEvent.ToolCallStarted(id = aId, name = a.name))
                    a.startedEmitted = true
                }
                tcDelta.function?.arguments?.let { frag ->
                    if (frag.isNotEmpty()) {
                        a.arguments.append(frag)
                        val id = a.id
                        if (id != null) emit(AiStreamEvent.ToolCallArgumentsDelta(id = id, argumentsFragment = frag))
                    }
                }
            }

            choice.finishReason?.let { fr ->
                finishReason = mapFinishReason(fr)
            }
            chunk.usage?.let {
                usage = AiUsage(
                    inputTokens = it.promptTokens,
                    outputTokens = it.completionTokens,
                    totalTokens = it.totalTokens,
                )
            }
        }

        // If stream failed, do not emit Completed
        if (failed) return@flow

        // After SSE stream end: emit ToolCallReady for each fully-assembled tool call (in index order)
        val sortedIndices = acc.keys.sorted()
        val toolCalls = mutableListOf<AiToolCall>()
        for (idx in sortedIndices) {
            val a = acc[idx] ?: continue
            val aId = a.id
            val aName = a.name
            if (aId != null && aName != null) {
                val call = AiToolCall(id = aId, name = aName, argumentsJson = a.arguments.toString().ifEmpty { "{}" })
                emit(AiStreamEvent.ToolCallReady(call))
                toolCalls.add(call)
            }
        }

        // Emit terminal Completed
        val text = accumulatedText.toString()
        emit(AiStreamEvent.Completed(
            message = AiMessage(
                role = AiRole.Assistant,
                content = if (text.isNotEmpty()) listOf(AiContent.Text(text)) else emptyList(),
                toolCalls = toolCalls,
            ),
            text = text,
            usage = usage,
            finishReason = finishReason,
        ))
    }

    private fun mapFinishReason(s: String): AiFinishReason = when (s) {
        "stop" -> AiFinishReason.Stop
        "length" -> AiFinishReason.Length
        "tool_calls", "function_call" -> AiFinishReason.ToolCalls
        "content_filter" -> AiFinishReason.ContentFilter
        else -> AiFinishReason.Other
    }
}
