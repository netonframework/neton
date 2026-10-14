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
    logSink: neton.ai.AiLogSink? = null,
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

        val agent = request.agent
        val workingMessages = request.messages.toMutableList()
        var lastCompleted: AiStreamEvent.Completed? = null
        var brokeForFallback = false
        val steps = mutableListOf<neton.ai.AiStep>()

        for (round in 1..request.maxToolRounds) {
            // prepareStep 是控制逻辑：异常照常抛出
            val stepCtx = neton.ai.AiStepContext(round, steps.toList(), workingMessages.toList(), modelId)
            val overrides = agent.prepareStep?.prepare(stepCtx)
            overrides?.messages?.let { workingMessages.clear(); workingMessages += it }
            val stepTools = overrides?.tools ?: request.tools
            val stepModelId = overrides?.model ?: modelId
            val stepModel = if (stepModelId == modelId) model else registry.get(stepModelId.providerId)?.streamingTextModel(stepModelId.modelName)
            if (stepModel == null) {
                send(AiStreamEvent.Failed(AiError.ModelNotFound(stepModelId.toString(), "prepareStep selected unknown model '$stepModelId'")))
                return@channelFlow
            }
            observe(logSink, "onStepStart") { agent.callbacks?.onStepStart(stepCtx.copy(messages = workingMessages.toList(), model = stepModelId)) }
            val startMs = kotlin.time.Clock.System.now().toEpochMilliseconds()
            var firstEventRead = false
            var roundCompleted: AiStreamEvent.Completed? = null
            var roundFailed: AiStreamEvent.Failed? = null
            val roundCalls = mutableListOf<AiToolCall>()

            val providerFlow = stepModel.stream(buildSpiRequest(request, workingMessages, stepTools, overrides?.toolChoice))

            try {
                providerFlow.collect { event ->
                    if (!firstEventRead) {
                        firstEventRead = true
                        if (!startedEmitted && event !is AiStreamEvent.Failed) {
                            send(AiStreamEvent.Started(modelId.providerId, modelId.modelName))
                            startedEmitted = true
                        }
                    }
                    when (event) {
                        is AiStreamEvent.Started ->
                            error("Provider MUST NOT emit Started; AiClient owns this event")
                        is AiStreamEvent.ToolResultReady ->
                            error("Provider MUST NOT emit ToolResultReady; AiClient owns local executor lifecycle")
                        is AiStreamEvent.StepFinished ->
                            error("Provider MUST NOT emit StepFinished; AiClient owns step lifecycle")
                        is AiStreamEvent.Completed -> roundCompleted = event
                        is AiStreamEvent.Failed -> roundFailed = event
                        is AiStreamEvent.ToolCallReady -> {
                            roundCalls += event.call
                            send(event)
                        }
                        is AiStreamEvent.TextDelta,
                        is AiStreamEvent.ReasoningDelta,
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
                    errorKind = failedSnap.error.kind,
                ))
                val canFallback = !startedEmitted && failedSnap.error.isFallbackEligible() &&
                    candidateIdx < candidates.lastIndex && request.modelPolicy != null
                if (canFallback) {
                    lastError = failedSnap.error
                    brokeForFallback = true
                    break
                }
                send(failedSnap)
                return@channelFlow
            }

            if (completedSnap == null) {
                // Provider stream ended without Completed or Failed — treat as unknown failure
                send(AiStreamEvent.Failed(AiError.Unknown("Provider stream ended without Completed or Failed event", null)))
                return@channelFlow
            }

            lastCompleted = completedSnap

            suspend fun finishStep(calls: List<AiToolCall>, results: List<neton.ai.AiToolResult>, finish: AiFinishReason) {
                val step = neton.ai.AiStep(round, stepModelId.providerId, stepModelId.modelName, completedSnap.message, calls, results, completedSnap.usage, finish)
                steps += step
                observe(logSink, "onStepFinish") { agent.callbacks?.onStepFinish(step) }
                send(AiStreamEvent.StepFinished(step))
            }

            if (roundCalls.isEmpty()) {
                // Final response — no tool calls this round
                finishStep(emptyList(), emptyList(), completedSnap.finishReason)
                send(completedSnap.copy(steps = steps.toList()))
                return@channelFlow
            }

            // Tool round: execute locally
            workingMessages += completedSnap.message
            val outcome = executeStepTools(roundCalls.toList(), stepTools, agent, round, request.metadata, workingMessages.toList(), logSink) {
                send(AiStreamEvent.ToolResultReady(it))
            }
            when (outcome) {
                is StepTools.HandedBack -> {
                    // No executor or approval pending: caller handles tool calls
                    finishStep(roundCalls.toList(), emptyList(), AiFinishReason.ToolCalls)
                    send(completedSnap.copy(finishReason = AiFinishReason.ToolCalls, steps = steps.toList(), pendingToolCalls = outcome.calls))
                    return@channelFlow
                }
                is StepTools.Executed -> {
                    outcome.results.forEach { workingMessages += toolResultMessage(it) }
                    finishStep(roundCalls.toList(), outcome.results, completedSnap.finishReason)
                }
            }
            if (agent.stopWhen.any { it.shouldStop(steps) }) {
                send(completedSnap.copy(finishReason = AiFinishReason.ToolCalls, steps = steps.toList()))
                return@channelFlow
            }
            // Continue to next round
        }

        if (brokeForFallback) continue

        // maxToolRounds exhausted — last response had tool calls
        if (lastCompleted != null) {
            send(lastCompleted.copy(finishReason = AiFinishReason.ToolCalls, steps = steps.toList()))
            return@channelFlow
        }
        // No response at all from this candidate (shouldn't happen if loop ran)
    }

    // All candidates exhausted without success
    send(AiStreamEvent.Failed(lastError ?: AiError.Unknown("No candidate model succeeded", null)))
}

private fun buildSpiRequest(
    request: StreamTextRequest,
    messages: List<AiMessage>,
    tools: List<neton.ai.AiToolDefinition> = request.tools,
    toolChoice: neton.ai.ToolChoice? = null,
): ProviderCallRequest =
    ProviderCallRequest(
        messages = messages,
        tools = tools,
        toolChoice = toolChoice ?: request.toolChoice,
        temperature = request.temperature,
        maxTokens = request.maxTokens,
        topP = request.topP,
        stopSequences = request.stopSequences,
        metadata = request.metadata,
        options = neton.ai.protocol.ir.CallOptions(
            reasoning = request.reasoning,
            responseFormat = request.responseFormat,
            providerOptions = request.providerOptions,
        ),
    )
