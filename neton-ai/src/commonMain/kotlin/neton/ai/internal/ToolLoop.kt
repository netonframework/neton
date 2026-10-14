// neton-ai/src/commonMain/kotlin/neton/ai/internal/ToolLoop.kt
package neton.ai.internal

import kotlinx.coroutines.CancellationException
import neton.ai.AiStep
import neton.ai.AiStepContext
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
    logSink: neton.ai.AiLogSink? = null,
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

        val agent = request.agent
        val workingMessages = request.messages.toMutableList()
        val accumulatedCalls = mutableListOf<AiToolCall>()
        val accumulatedResults = mutableListOf<AiToolResult>()
        val perRoundUsage = mutableListOf<AiUsage?>()
        val steps = mutableListOf<AiStep>()
        var lastResp: ProviderCallResponse? = null
        var lastModelId = modelId
        var brokeForFallback = false

        fun result(resp: ProviderCallResponse, finish: AiFinishReason, round: Int, pending: List<AiToolCall> = emptyList()) = GenerateTextResult(
            text = resp.text,
            message = resp.message,
            toolCalls = accumulatedCalls.toList(),
            toolResults = accumulatedResults.toList(),
            usage = aggregateUsage(perRoundUsage),
            finishReason = finish,
            providerId = lastModelId.providerId,
            modelName = lastModelId.modelName,
            rounds = round,
            steps = steps.toList(),
            pendingToolCalls = pending,
        )

        for (round in 1..request.maxToolRounds) {
            // prepareStep 是控制逻辑：异常照常抛出
            val stepCtx = AiStepContext(round, steps.toList(), workingMessages.toList(), modelId)
            val overrides = agent.prepareStep?.prepare(stepCtx)
            overrides?.messages?.let { workingMessages.clear(); workingMessages += it }
            val stepTools = overrides?.tools ?: request.tools
            val stepModelId = overrides?.model ?: modelId
            val stepModel = if (stepModelId == modelId) model else registry.get(stepModelId.providerId)?.textModel(stepModelId.modelName)
                ?: throw AiException(AiError.ModelNotFound(stepModelId.toString(), "prepareStep selected unknown model '$stepModelId'"))
            lastModelId = stepModelId
            observe(logSink, "onStepStart") { agent.callbacks?.onStepStart(stepCtx.copy(messages = workingMessages.toList(), model = stepModelId)) }

            val startMs = kotlin.time.Clock.System.now().toEpochMilliseconds()
            val resp: ProviderCallResponse = try {
                stepModel.generate(buildSpiRequest(request, workingMessages, stepTools, overrides?.toolChoice))
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiException) {
                val err = e.error
                // Round 1 + fallback eligible + more candidates → break inner loop, outer for advances to next candidate
                val canFallback = round == 1 && err.isFallbackEligible() &&
                    candidateIdx < candidates.lastIndex && request.modelPolicy != null
                recorder.record(AiUsageEvent(
                    requestId = requestId,
                    providerId = stepModelId.providerId,
                    modelName = stepModelId.modelName,
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
                providerId = stepModelId.providerId,
                modelName = stepModelId.modelName,
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
                val step = AiStep(round, stepModelId.providerId, stepModelId.modelName, resp.message, emptyList(), emptyList(), resp.usage, resp.finishReason)
                steps += step
                observe(logSink, "onStepFinish") { agent.callbacks?.onStepFinish(step) }
                return result(resp, resp.finishReason, round)
            }

            accumulatedCalls += resp.toolCalls
            workingMessages += resp.message

            when (val outcome = executeStepTools(resp.toolCalls, stepTools, agent, round, request.metadata, workingMessages.toList(), logSink)) {
                is StepTools.HandedBack -> {
                    // 没有本地执行器或审批挂起 → 交还调用方
                    val step = AiStep(round, stepModelId.providerId, stepModelId.modelName, resp.message, resp.toolCalls, emptyList(), resp.usage, AiFinishReason.ToolCalls)
                    steps += step
                    observe(logSink, "onStepFinish") { agent.callbacks?.onStepFinish(step) }
                    return result(resp, AiFinishReason.ToolCalls, round, outcome.calls)
                }
                is StepTools.Executed -> {
                    accumulatedResults += outcome.results
                    outcome.results.forEach { workingMessages += toolResultMessage(it) }
                    val step = AiStep(round, stepModelId.providerId, stepModelId.modelName, resp.message, resp.toolCalls, outcome.results, resp.usage, resp.finishReason)
                    steps += step
                    observe(logSink, "onStepFinish") { agent.callbacks?.onStepFinish(step) }
                }
            }
            if (agent.stopWhen.any { it.shouldStop(steps) }) return result(resp, AiFinishReason.ToolCalls, round)
        }

        // If we broke out of the inner loop due to fallback, continue to next candidate
        if (brokeForFallback) continue

        // maxToolRounds exhausted → return last response with ToolCalls finish reason
        lastResp?.let { return result(it, AiFinishReason.ToolCalls, request.maxToolRounds) }
        // Fallback path: continue to next candidate (loop again)
    }

    throw AiException(lastError ?: AiError.Unknown("No candidate model succeeded", null))
}

private fun buildSpiRequest(
    request: GenerateTextRequest,
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
