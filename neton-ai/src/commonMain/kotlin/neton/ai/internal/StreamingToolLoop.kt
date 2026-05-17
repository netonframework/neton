// neton-ai/src/commonMain/kotlin/neton/ai/internal/StreamingToolLoop.kt
package neton.ai.internal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import neton.ai.AiContent
import neton.ai.AiError
import neton.ai.AiException
import neton.ai.AiFinishReason
import neton.ai.AiMessage
import neton.ai.AiModelId
import neton.ai.AiRole
import neton.ai.AiStreamEvent
import neton.ai.AiToolCall
import neton.ai.AiToolResult
import neton.ai.StreamTextRequest
import neton.ai.isFallbackEligible
import neton.ai.kind
import neton.ai.provider.ProviderCallRequest
import neton.ai.provider.ProviderRegistry
import neton.ai.routing.ModelRouter
import neton.ai.usage.AiUsageEvent
import neton.ai.usage.AiUsageRecorder

/**
 * Streaming tool loop per spec §3.7. See StreamingToolLoopTest for exhaustive branch coverage.
 *
 * High-level:
 *   candidates = router.resolve(request.model, request.modelPolicy)
 *   for each candidate:
 *     for round in 1..maxToolRounds:
 *       collect provider flow; emit Started after first event
 *       if round has tool calls → execute locally, continue
 *       else → emit Completed, done
 *     exhausted → emit Completed(ToolCalls)
 *   emit Failed(lastError)
 *
 * Fallback rules (spec §3.8):
 *   - Before Started (first event not yet read) + fallback-eligible error + more candidates → try next
 *   - After Started → never fallback, emit Failed
 *   - CancellationException → rethrow, do NOT emit Failed
 */
internal fun runStreamingToolLoop(
    request: StreamTextRequest,
    registry: ProviderRegistry,
    router: ModelRouter,
    recorder: AiUsageRecorder,
): Flow<AiStreamEvent> = channelFlow {
    val candidates: List<AiModelId> = router.resolve(request.model, request.modelPolicy)
    if (candidates.isEmpty()) {
        send(AiStreamEvent.Failed(AiError.InvalidRequest("Router returned no candidates")))
        return@channelFlow
    }

    val requestId: String? = request.metadata["requestId"] ?: request.metadata["traceId"]
    var lastError: AiError? = null
    var startedEmitted = false

    for ((candidateIdx, modelId) in candidates.withIndex()) {
        val provider = registry.get(modelId.providerId)
        val model = provider?.streamingTextModel(modelId.modelName)
        if (model == null) {
            lastError = AiError.ModelNotFound(
                modelId = modelId.toString(),
                message = "Provider '${modelId.providerId}' or model '${modelId.modelName}' not registered",
            )
            // ModelNotFound: if policy-driven and more candidates, try next (fail-soft on misconfiguration)
            if (request.modelPolicy != null && candidateIdx < candidates.lastIndex) continue
            send(AiStreamEvent.Failed(lastError))
            return@channelFlow
        }

        var workingMessages = request.messages.toMutableList()
        var lastCompleted: AiStreamEvent.Completed? = null
        var brokeForFallback = false

        for (round in 1..request.maxToolRounds) {
            val startMs = kotlin.time.Clock.System.now().toEpochMilliseconds()
            var firstEventRead = false
            var roundCompleted: AiStreamEvent.Completed? = null
            var roundFailed: AiStreamEvent.Failed? = null
            val roundCalls = mutableListOf<AiToolCall>()

            val providerFlow = model.stream(buildSpiRequest(request, workingMessages))

            try {
                providerFlow.collect { event ->
                    if (!firstEventRead) {
                        firstEventRead = true
                        if (!startedEmitted) {
                            send(AiStreamEvent.Started(modelId.providerId, modelId.modelName))
                            startedEmitted = true
                        }
                    }
                    when (event) {
                        is AiStreamEvent.Started ->
                            error("Provider MUST NOT emit Started; AiClient owns this event")
                        is AiStreamEvent.ToolResultReady ->
                            error("Provider MUST NOT emit ToolResultReady; AiClient owns local executor lifecycle")
                        is AiStreamEvent.Completed -> roundCompleted = event
                        is AiStreamEvent.Failed -> roundFailed = event
                        is AiStreamEvent.ToolCallReady -> {
                            roundCalls += event.call
                            send(event)
                        }
                        is AiStreamEvent.TextDelta,
                        is AiStreamEvent.ToolCallStarted,
                        is AiStreamEvent.ToolCallArgumentsDelta -> send(event)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiException) {
                val err = e.error
                val durationMs = kotlin.time.Clock.System.now().toEpochMilliseconds() - startMs
                recorder.record(AiUsageEvent(
                    requestId = requestId,
                    providerId = modelId.providerId,
                    modelName = modelId.modelName,
                    usage = null,
                    round = round,
                    requestMetadata = request.metadata,
                    timestampEpochMillis = startMs,
                    durationMillis = durationMs,
                    finishReason = null,
                    success = false,
                    errorKind = err.kind,
                ))
                // Fallback eligible only before first event + more candidates + policy-driven
                val canFallback = !startedEmitted && err.isFallbackEligible() &&
                    candidateIdx < candidates.lastIndex && request.modelPolicy != null
                if (canFallback) {
                    lastError = err
                    brokeForFallback = true
                    break
                }
                send(AiStreamEvent.Failed(err))
                return@channelFlow
            }

            // Snapshot vars after collect — Kotlin cannot smart-cast captured vars
            val completedSnap: AiStreamEvent.Completed? = roundCompleted
            val failedSnap: AiStreamEvent.Failed? = roundFailed

            // Record usage for this round if provider emitted Completed
            if (completedSnap != null) {
                val durationMs = kotlin.time.Clock.System.now().toEpochMilliseconds() - startMs
                recorder.record(AiUsageEvent(
                    requestId = requestId,
                    providerId = modelId.providerId,
                    modelName = modelId.modelName,
                    usage = completedSnap.usage,
                    round = round,
                    requestMetadata = request.metadata,
                    timestampEpochMillis = startMs,
                    durationMillis = durationMs,
                    finishReason = completedSnap.finishReason,
                    success = true,
                    errorKind = null,
                ))
            }

            if (failedSnap != null) {
                send(failedSnap)
                return@channelFlow
            }

            if (completedSnap == null) {
                // Provider stream ended without Completed or Failed — treat as unknown failure
                send(AiStreamEvent.Failed(AiError.Unknown("Provider stream ended without Completed or Failed event", null)))
                return@channelFlow
            }

            lastCompleted = completedSnap

            if (roundCalls.isEmpty()) {
                // Final response — no tool calls this round
                send(completedSnap)
                return@channelFlow
            }

            // Tool round: execute locally
            workingMessages += completedSnap.message

            for (call in roundCalls) {
                val def = request.tools.firstOrNull { it.name == call.name }
                if (def?.executor == null) {
                    // No executor: caller handles tool calls; emit Completed with ToolCalls finish reason
                    send(completedSnap.copy(finishReason = AiFinishReason.ToolCalls))
                    return@channelFlow
                }
                val result = try {
                    AiToolResult(call.id, def.executor.execute(call.argumentsJson), isError = false)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    AiToolResult(call.id, e.message ?: "tool execution failed", isError = true)
                }
                send(AiStreamEvent.ToolResultReady(result))
                workingMessages += AiMessage(
                    role = AiRole.Tool,
                    content = listOf(AiContent.Text(result.content)),
                    toolCallId = call.id,
                )
            }
            // Continue to next round
        }

        if (brokeForFallback) continue

        // maxToolRounds exhausted — last response had tool calls
        if (lastCompleted != null) {
            send(lastCompleted.copy(finishReason = AiFinishReason.ToolCalls))
            return@channelFlow
        }
        // No response at all from this candidate (shouldn't happen if loop ran)
    }

    // All candidates exhausted without success
    send(AiStreamEvent.Failed(lastError ?: AiError.Unknown("No candidate model succeeded", null)))
}

private fun buildSpiRequest(request: StreamTextRequest, messages: List<AiMessage>): ProviderCallRequest =
    ProviderCallRequest(
        messages = messages,
        tools = request.tools,
        toolChoice = request.toolChoice,
        temperature = request.temperature,
        maxTokens = request.maxTokens,
        topP = request.topP,
        stopSequences = request.stopSequences,
        metadata = request.metadata,
    )
