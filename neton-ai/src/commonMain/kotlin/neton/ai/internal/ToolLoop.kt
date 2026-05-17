// neton-ai/src/commonMain/kotlin/neton/ai/internal/ToolLoop.kt
package neton.ai.internal

import kotlinx.coroutines.CancellationException
import neton.ai.AiContent
import neton.ai.AiError
import neton.ai.AiException
import neton.ai.AiFinishReason
import neton.ai.AiMessage
import neton.ai.AiModelId
import neton.ai.AiRole
import neton.ai.AiToolCall
import neton.ai.AiToolResult
import neton.ai.AiUsage
import neton.ai.GenerateTextRequest
import neton.ai.GenerateTextResult
import neton.ai.isFallbackEligible
import neton.ai.kind
import neton.ai.provider.ProviderCallRequest
import neton.ai.provider.ProviderCallResponse
import neton.ai.provider.ProviderRegistry
import neton.ai.routing.ModelRouter
import neton.ai.usage.AiUsageEvent
import neton.ai.usage.AiUsageRecorder

/**
 * Non-streaming tool loop per spec §3.7. See ToolLoopTest for the exhaustive branch coverage.
 *
 * High-level:
 *   candidates = router.resolve(request.model, request.modelPolicy)
 *   for each candidate in order:
 *     for round in 1..maxToolRounds:
 *       try: resp = model.generate(...)
 *       record usage
 *       if no toolCalls → return success
 *       if any tool has no executor → return early with FinishReason.ToolCalls
 *       execute tools, append result messages, continue
 *     exhausted → return with FinishReason.ToolCalls
 *   throw lastError
 *
 * Fallback rules:
 *   - round 1 + fallback-eligible error + more candidates → break inner loop, advance to next candidate
 *   - round >= 2 → never fallback (state already accumulated)
 *   - non-fallback-eligible error (auth/semantic) → propagate immediately
 */
internal suspend fun runToolLoop(
    request: GenerateTextRequest,
    registry: ProviderRegistry,
    router: ModelRouter,
    recorder: AiUsageRecorder,
): GenerateTextResult {
    val candidates: List<AiModelId> = router.resolve(request.model, request.modelPolicy)
    if (candidates.isEmpty()) {
        throw AiException(AiError.InvalidRequest("Router returned no candidates"))
    }

    val requestId: String? = request.metadata["requestId"] ?: request.metadata["traceId"]
    var lastError: AiError? = null

    for ((candidateIdx, modelId) in candidates.withIndex()) {
        val provider = registry.get(modelId.providerId)
        val model = provider?.textModel(modelId.modelName)
        if (model == null) {
            lastError = AiError.ModelNotFound(
                modelId = modelId.toString(),
                message = "Provider '${modelId.providerId}' or model '${modelId.modelName}' not registered",
            )
            // ModelNotFound is NOT fallback-eligible per AiError.isFallbackEligible.
            // But router gave us multiple candidates intentionally (policy); if one is misconfigured,
            // try the next so the request can still succeed (fail-soft on policy misconfiguration).
            if (request.modelPolicy != null && candidateIdx < candidates.lastIndex) continue
            throw AiException(lastError)
        }

        val workingMessages = request.messages.toMutableList()
        val accumulatedCalls = mutableListOf<AiToolCall>()
        val accumulatedResults = mutableListOf<AiToolResult>()
        val perRoundUsage = mutableListOf<AiUsage?>()
        var lastResp: ProviderCallResponse? = null
        var brokeForFallback = false

        for (round in 1..request.maxToolRounds) {
            val startMs = kotlin.time.Clock.System.now().toEpochMilliseconds()
            val resp: ProviderCallResponse = try {
                model.generate(buildSpiRequest(request, workingMessages))
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiException) {
                val err = e.error
                // Round 1 + fallback eligible + more candidates → break inner loop, outer for advances to next candidate
                val canFallback = round == 1 && err.isFallbackEligible() &&
                    candidateIdx < candidates.lastIndex && request.modelPolicy != null
                recorder.record(AiUsageEvent(
                    requestId = requestId,
                    providerId = modelId.providerId,
                    modelName = modelId.modelName,
                    usage = null,
                    round = round,
                    requestMetadata = request.metadata,
                    timestampEpochMillis = startMs,
                    durationMillis = kotlin.time.Clock.System.now().toEpochMilliseconds() - startMs,
                    finishReason = null,
                    success = false,
                    errorKind = err.kind,
                ))
                if (canFallback) {
                    lastError = err
                    brokeForFallback = true
                    break  // exits inner round loop, outer for advances to next candidate
                }
                throw e
            }
            recorder.record(AiUsageEvent(
                requestId = requestId,
                providerId = modelId.providerId,
                modelName = modelId.modelName,
                usage = resp.usage,
                round = round,
                requestMetadata = request.metadata,
                timestampEpochMillis = startMs,
                durationMillis = kotlin.time.Clock.System.now().toEpochMilliseconds() - startMs,
                finishReason = resp.finishReason,
                success = true,
                errorKind = null,
            ))
            perRoundUsage += resp.usage
            lastResp = resp

            if (resp.toolCalls.isEmpty()) {
                return GenerateTextResult(
                    text = resp.text,
                    message = resp.message,
                    toolCalls = accumulatedCalls,
                    toolResults = accumulatedResults,
                    usage = aggregateUsage(perRoundUsage),
                    finishReason = resp.finishReason,
                    providerId = modelId.providerId,
                    modelName = modelId.modelName,
                    rounds = round,
                )
            }

            accumulatedCalls += resp.toolCalls
            workingMessages += resp.message

            var returnedEarly = false
            for (call in resp.toolCalls) {
                val def = request.tools.firstOrNull { it.name == call.name }
                if (def?.executor == null) {
                    // No local executor → return early; caller handles tool calls
                    return GenerateTextResult(
                        text = resp.text,
                        message = resp.message,
                        toolCalls = accumulatedCalls,
                        toolResults = accumulatedResults,
                        usage = aggregateUsage(perRoundUsage),
                        finishReason = AiFinishReason.ToolCalls,
                        providerId = modelId.providerId,
                        modelName = modelId.modelName,
                        rounds = round,
                    )
                }
                val result = try {
                    AiToolResult(call.id, def.executor.execute(call.argumentsJson), isError = false)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    AiToolResult(call.id, e.message ?: "tool execution failed", isError = true)
                }
                accumulatedResults += result
                workingMessages += AiMessage(
                    role = AiRole.Tool,
                    content = listOf(AiContent.Text(result.content)),
                    toolCallId = call.id,
                )
            }
        }

        // If we broke out of the inner loop due to fallback, continue to next candidate
        if (brokeForFallback) continue

        // maxToolRounds exhausted → return last response with ToolCalls finish reason
        if (lastResp != null) {
            return GenerateTextResult(
                text = lastResp.text,
                message = lastResp.message,
                toolCalls = accumulatedCalls,
                toolResults = accumulatedResults,
                usage = aggregateUsage(perRoundUsage),
                finishReason = AiFinishReason.ToolCalls,
                providerId = modelId.providerId,
                modelName = modelId.modelName,
                rounds = request.maxToolRounds,
            )
        }
        // Fallback path: continue to next candidate (loop again)
    }

    throw AiException(lastError ?: AiError.Unknown("No candidate model succeeded", null))
}

private fun buildSpiRequest(request: GenerateTextRequest, messages: List<AiMessage>): ProviderCallRequest =
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
