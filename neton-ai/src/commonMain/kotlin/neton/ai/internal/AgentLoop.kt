package neton.ai.internal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import neton.ai.*
import neton.ai.model.AiLanguageModel
import neton.ai.model.AiModelCallOptions
import neton.ai.model.AiWarning
import neton.ai.protocol.ir.*
import neton.ai.provider.ProviderRegistry
import neton.ai.routing.ModelRouter
import neton.ai.usage.AiUsageEvent
import neton.ai.usage.AiUsageRecorder
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * 多步生成循环（spec/ai.md §10）。`generate` 与 `stream` 共用：流式模式下边生成边发出事件，
 * 非流式模式用一次完整调用，事件不对外。
 *
 * 继续条件（全部满足才进入下一步）：本步的结束原因允许执行工具（Stop / ToolCalls）；有本地工具调用；
 * 每个本地调用都有了输出（执行结果、拒绝或参数错误）；没有挂起的审批；[AiRequest.stopWhen] 都不满足。
 */
internal class AgentLoop(
    private val registry: ProviderRegistry,
    private val router: ModelRouter,
    private val recorder: AiUsageRecorder,
    private val logSink: AiLogSink?,
) {
    private val clock = TimeSource.Monotonic

    /** 第一步在任何输出之前失败、且可以换下一个候选模型。 */
    private class Fallback(val error: AiError) : RuntimeException(error.message)

    suspend fun run(request: AiRequest, streaming: Boolean, emit: suspend (AiStreamPart) -> Unit): AiResult {
        val callbacks = request.callbacks
        observe(logSink, "onStart") { callbacks?.onStart(request) }
        try {
            val result = request.timeout?.totalMs?.let { ms ->
                try {
                    withTimeout(ms) { runCandidates(request, streaming, emit) }
                } catch (e: TimeoutCancellationException) {
                    throw AiException(AiError.Timeout("request exceeded the total timeout of ${ms}ms", e))
                }
            } ?: runCandidates(request, streaming, emit)
            observe(logSink, "onEnd") { callbacks?.onEnd(result) }
            return result
        } catch (e: AiException) {
            observe(logSink, "onError") { callbacks?.onError(e.error) }
            throw e
        }
    }

    private suspend fun runCandidates(request: AiRequest, streaming: Boolean, emit: suspend (AiStreamPart) -> Unit): AiResult {
        val candidates = router.resolve(request.model, request.modelPolicy)
        if (candidates.isEmpty()) throw AiException(AiError.InvalidRequest("router returned no candidate model"))
        var lastError: AiError? = null
        for ((i, candidate) in candidates.withIndex()) {
            val canFallback = request.modelPolicy != null && i < candidates.lastIndex
            try {
                return Run(request, candidate, streaming, emit, canFallback).execute()
            } catch (f: Fallback) {
                lastError = f.error
                logSink?.invoke("ai.fallback from=$candidate error=${f.error.kind}")
            }
        }
        throw AiException(lastError ?: AiError.Unknown("no candidate model succeeded", null))
    }

    private fun resolve(id: AiModelId): AiLanguageModel? = registry.get(id.providerId)?.languageModel(id.modelName)

    /** 一次调用的状态。 */
    private inner class Run(
        val request: AiRequest,
        val initialModel: AiModelId,
        val streaming: Boolean,
        val emit: suspend (AiStreamPart) -> Unit,
        val canFallback: Boolean,
    ) {
        val callbacks = request.callbacks
        val messages = request.messages.toMutableList()
        var instructions = request.instructions
        val responseMessages = mutableListOf<AiMessage>()
        val steps = mutableListOf<AiStep>()
        val discovered = HashSet<String>()
        var sourceCounter = 0

        suspend fun execute(): AiResult {
            if (resolve(initialModel) == null) {
                val err = AiError.ModelNotFound(initialModel.toString(), "provider '${initialModel.providerId}' or model '${initialModel.modelName}' is not registered")
                if (canFallback) throw Fallback(err)
                throw AiException(err)
            }
            resumeApprovals()
            var stepNumber = 1
            while (true) {
                val step = request.timeout?.stepMs?.let { ms ->
                    try {
                        withTimeout(ms) { runStep(stepNumber) }
                    } catch (e: TimeoutCancellationException) {
                        throw AiException(AiError.Timeout("step $stepNumber exceeded ${ms}ms", e))
                    }
                } ?: runStep(stepNumber)
                if (!step.shouldContinue || request.stopWhen.any { it.shouldStop(steps) }) break
                stepNumber++
            }
            val usage = steps.map { it.usage }.reduceOrNull { a, b -> a + b } ?: AiUsage.UNKNOWN
            return AiResult(steps.toList(), responseMessages.toList(), usage, request.output?.let { parseOutput(it) })
        }

        // ───────────────────────── 审批恢复 ─────────────────────────

        /**
         * 最后一条消息是对审批的答复时：在第一步之前执行被批准的调用、给被拒绝的调用写拒绝结果。
         * 被批准的调用会重新校验签名、参数、授权与审批策略——策略拒绝优先于调用方的批准。
         */
        private suspend fun resumeApprovals() {
            val last = messages.lastOrNull() ?: return
            if (last.role != AiRole.Tool) return
            val responses = last.content.filterIsInstance<AiContent.ToolApprovalResponse>()
            if (responses.isEmpty()) return

            val requests = HashMap<String, AiContent.ToolApprovalRequest>()
            val calls = HashMap<String, AiContent.ToolCall>()
            val answered = HashSet<String>()
            for (m in messages) for (c in m.content) when (c) {
                is AiContent.ToolApprovalRequest -> requests[c.approvalId] = c
                is AiContent.ToolCall -> calls[c.call.id] = c
                is AiContent.ToolResult -> if (!c.preliminary) answered += c.toolCallId
                else -> {}
            }
            val results = mutableListOf<AiContent.ToolResult>()
            val approved = mutableListOf<Pair<AiContent.ToolCall, AiContent.ToolApprovalRequest>>()
            for (r in responses) {
                val req = requests[r.approvalId] ?: throw AiException(AiError.InvalidRequest("unknown tool approval id '${r.approvalId}'"))
                val call = calls[req.toolCallId] ?: throw AiException(AiError.InvalidRequest("no tool call '${req.toolCallId}' for approval '${r.approvalId}'"))
                if (req.toolCallId in answered) continue
                if (!r.approved) {
                    results += AiContent.ToolResult(call.call.id, call.call.name, AiToolOutput.Denied(r.reason))
                    continue
                }
                request.toolApprovalSecret?.let { secret ->
                    val expected = sign(secret, req.approvalId, call.call)
                    if (req.signature == null || req.signature != expected) {
                        throw AiException(AiError.InvalidRequest("tool approval '${r.approvalId}' has a missing or invalid signature"))
                    }
                }
                approved += call to req
            }
            // 按模型原始的调用顺序执行
            val order = calls.keys.toList()
            approved.sortBy { order.indexOf(it.first.call.id) }
            val tools = request.tools
            val stepNumber = 0
            val executable = mutableListOf<Triple<AiTool, JsonElement, AiToolCallContext>>()
            for ((call, _) in approved) {
                val tool = tools.firstOrNull { it.name == call.call.name }
                val validated = tool?.let { validateInput(it, call.call) }
                if (tool == null || validated == null || validated.isFailure) {
                    val msg = validated?.exceptionOrNull()?.message ?: "tool '${call.call.name}' is no longer available"
                    results += AiContent.ToolResult(call.call.id, call.call.name, AiToolOutput.ErrorText(msg))
                    continue
                }
                val input = validated.getOrThrow()
                val ctx = context(call.call, input, stepNumber)
                val auth = request.authorizer?.authorize(tool, ctx)
                if (auth is AiApproval.Denied) { results += AiContent.ToolResult(call.call.id, tool.name, AiToolOutput.Denied(auth.reason)); continue }
                val policy = request.toolApproval?.approve(tool, input, ctx)
                if (policy is AiApproval.Denied) { results += AiContent.ToolResult(call.call.id, tool.name, AiToolOutput.Denied(policy.reason)); continue }
                if (tool.execute == null) continue
                executable += Triple(tool, input, ctx)
            }
            val executed = executeAll(executable, HashMap())
            val all = (results + executed).sortedBy { order.indexOf(it.toolCallId) }
            if (all.isNotEmpty()) {
                val msg = AiMessage(AiRole.Tool, all)
                messages += msg
                responseMessages += msg
                all.forEach { emit(AiStreamPart.ToolResult(it)) }
            }
        }

        // ───────────────────────── 一步 ─────────────────────────

        inner class StepOutcome(val shouldContinue: Boolean)

        private suspend fun runStep(stepNumber: Int): StepOutcome {
            val stepMark = clock.markNow()
            val prepared = request.prepareStep?.prepare(AiStepContext(stepNumber, steps.toList(), initialModel, instructions, messages.toList()))
            prepared?.instructions?.let { instructions = it }
            prepared?.messages?.let { messages.clear(); messages += it }
            val modelId = prepared?.model ?: initialModel
            val model = resolve(modelId) ?: throw AiException(AiError.ModelNotFound(modelId.toString(), "model '$modelId' is not registered"))
            val toolChoice = prepared?.toolChoice ?: request.toolChoice
            val active = prepared?.activeTools ?: request.activeTools
            val visible = visibleTools(active)
            val settings = prepared?.settings ?: request.settings
            val providerOptions = mergeProviderOptions(request.providerOptions, prepared?.providerOptions)

            val chat = ModelMapping.toChatRequest(
                model = modelId.modelName,
                instructions = instructions,
                messages = messages,
                tools = visible,
                toolChoice = toolChoice,
                settings = settings,
                responseFormat = request.output?.responseFormat,
                providerOptions = providerOptions,
                stream = streaming,
            )
            observe(logSink, "onStepStart") { callbacks?.onStepStart(AiStepContext(stepNumber, steps.toList(), modelId, instructions, messages.toList())) }

            // 模型调用
            observe(logSink, "onModelCallStart") { callbacks?.onModelCallStart(stepNumber, modelId) }
            val modelMark = clock.markNow()
            val call = try {
                if (streaming) streamCall(model, chat, stepNumber, modelId) else generateCall(model, chat, stepNumber)
            } catch (e: AiException) {
                recordUsage(modelId, stepNumber, null, null, modelMark, e.error)
                if (stepNumber == 1 && steps.isEmpty() && canFallback && e.error.isFallbackEligible() && !emittedOutput) throw Fallback(e.error)
                throw e
            }
            val modelTimeMs = modelMark.elapsedNow().inWholeMilliseconds
            recordUsage(modelId, stepNumber, call.usage, call.finishReason, modelMark, null)
            observe(logSink, "onModelCallEnd") { callbacks?.onModelCallEnd(stepNumber, modelId, call.usage, call.finishReason) }

            // toolChoice 强制：要求调用工具却没有调用
            val validCalls = call.content.filterIsInstance<AiContent.ToolCall>().filter { !it.invalid }
            if (call.finishReason == AiFinishReason.Stop || call.finishReason == AiFinishReason.ToolCalls) {
                val violated = when (toolChoice) {
                    neton.ai.ToolChoice.Required -> validCalls.isEmpty()
                    is neton.ai.ToolChoice.Named -> validCalls.none { it.call.name == toolChoice.name }
                    else -> false
                }
                if (violated && visible.isNotEmpty()) throw AiException(AiError.ToolChoiceViolation(
                    "toolChoice $toolChoice required a tool call but model '$modelId' returned none"))
            }

            // 工具
            val toolsMark = mutableMapOf<String, Long>()
            val execAllowed = call.finishReason == AiFinishReason.Stop || call.finishReason == AiFinishReason.ToolCalls
            val outcome = if (execAllowed) handleTools(call.content, stepNumber, toolsMark) else ToolPhase(emptyList(), emptyList(), emptyList(), false)

            val assistantContent = call.content + outcome.approvalRequests
            val assistant = AiMessage(AiRole.Assistant, assistantContent)
            val toolMessage = (outcome.approvalResponses + outcome.results).takeIf { it.isNotEmpty() }?.let { AiMessage(AiRole.Tool, it) }
            val stepMessages = listOfNotNull(assistant, toolMessage)
            messages += stepMessages
            responseMessages += stepMessages

            val sources = ModelMapping.sourcesOf(call.content, sourceCounter).also { sourceCounter += it.size }
            sources.forEach { emit(AiStreamPart.Source(it)) }
            val step = AiStep(
                stepNumber = stepNumber,
                model = modelId,
                content = assistantContent + outcome.approvalResponses + outcome.results + sources,
                finishReason = call.finishReason,
                rawFinishReason = call.rawFinishReason,
                usage = call.usage,
                warnings = call.warnings,
                performance = AiStepPerformance(
                    stepTimeMs = stepMark.elapsedNow().inWholeMilliseconds,
                    modelTimeMs = modelTimeMs,
                    timeToFirstOutputMs = call.timeToFirstOutputMs,
                    outputTokensPerSecond = call.usage.outputTokens?.takeIf { modelTimeMs > 0 }?.let { it * 1000.0 / modelTimeMs },
                    toolExecutionMs = toolsMark.toMap(),
                ),
                responseMessages = stepMessages,
            )
            steps += step
            emit(AiStreamPart.StepFinish(step))
            observe(logSink, "onStepEnd") { callbacks?.onStepEnd(step) }

            val clientCalls = call.content.filterIsInstance<AiContent.ToolCall>().filter { !it.providerExecuted }
            val shouldContinue = execAllowed && clientCalls.isNotEmpty() && !outcome.pending &&
                outcome.results.size == clientCalls.size
            return StepOutcome(shouldContinue)
        }

        private fun visibleTools(active: Set<String>?): List<AiTool> {
            val visible = request.tools.filter { t ->
                (active == null || t.name in active) && (!t.deferLoading || t.name in discovered)
            }
            val order = request.toolOrder
            return visible.sortedWith(compareBy<AiTool> { order.indexOf(it.name).let { i -> if (i < 0) Int.MAX_VALUE else i } }.thenBy { it.name })
        }

        // ───────────────────────── 模型调用 ─────────────────────────

        inner class ModelCall(
            val content: List<AiContent>,
            val finishReason: AiFinishReason,
            val rawFinishReason: String?,
            val usage: AiUsage,
            val warnings: List<AiWarning>,
            val timeToFirstOutputMs: Long?,
        )

        /** 流式模式下本步是否已经对外发出过输出（发出后不再回退或重试）。 */
        var emittedOutput = false

        private val callOptions = AiModelCallOptions(headers = request.headers, metadata = request.metadata)

        private suspend fun generateCall(model: AiLanguageModel, chat: ChatRequest, stepNumber: Int): ModelCall {
            val r = withRetries(request.maxRetries) { model.generate(chat, callOptions) }
            val raw = r.response.content.flatMap(ModelMapping::toContent)
            val content = raw.map { if (it is AiContent.ToolCall) validateCall(it, stepNumber) else it }
            emit(AiStreamPart.StepStart(stepNumber, AiModelId(model.provider, model.modelId), r.warnings))
            val hasCalls = content.any { it is AiContent.ToolCall }
            return ModelCall(content, ModelMapping.toFinish(r.response.finishReason, hasCalls), r.response.rawFinishReason,
                ModelMapping.toUsage(r.response.usage), r.warnings, null)
        }

        /** 流式调用：首个事件之前的失败可以重试；之后的失败直接上抛。 */
        private suspend fun streamCall(model: AiLanguageModel, chat: ChatRequest, stepNumber: Int, modelId: AiModelId): ModelCall {
            var attempt = 0
            var delayMs = 2_000L
            while (true) {
                val acc = StreamAccumulator(stepNumber)
                try {
                    val s = model.stream(chat, callOptions)
                    emit(AiStreamPart.StepStart(stepNumber, modelId, s.warnings))
                    collectWithTimeouts(s.events, acc)
                    val content = acc.content()
                    val hasCalls = content.any { it is AiContent.ToolCall }
                    return ModelCall(content, ModelMapping.toFinish(acc.finish, hasCalls), acc.rawFinish,
                        ModelMapping.toUsage(acc.usage), s.warnings, acc.firstOutputMs)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: AiException) {
                    if (acc.started || attempt >= request.maxRetries || !e.error.isFallbackEligible()) throw e
                    kotlinx.coroutines.delay(delayMs)
                    delayMs *= 2
                    attempt++
                }
            }
        }

        private suspend fun collectWithTimeouts(events: Flow<StreamEvent>, acc: StreamAccumulator) {
            val first = request.timeout?.firstChunkMs
            val chunk = request.timeout?.chunkMs
            if (first == null && chunk == null) {
                events.collect { acc.accept(it) }
                return
            }
            coroutineScope {
                val ch = Channel<StreamEvent>(Channel.BUFFERED)
                val producer = launch {
                    try { events.collect { ch.send(it) }; ch.close() } catch (e: Throwable) { ch.close(e) }
                }
                var deadline = first?.let { clock.markNow() + it.milliseconds }
                while (true) {
                    val remaining = deadline?.let { -it.elapsedNow().inWholeMilliseconds }
                    val r = if (remaining == null) ch.receiveCatching()
                        else withTimeoutOrNull(remaining.coerceAtLeast(1)) { ch.receiveCatching() } ?: run {
                            producer.cancel()
                            throw AiException(AiError.Timeout(if (acc.firstOutputMs == null) "no output within ${first}ms" else "no output for ${chunk}ms", null))
                        }
                    if (r.isClosed) { r.exceptionOrNull()?.let { throw it }; break }
                    val e = r.getOrThrow()
                    val wasOutput = acc.accept(e)
                    if (wasOutput) deadline = chunk?.let { clock.markNow() + it.milliseconds }
                }
            }
        }

        /** 把 IR 流事件转成 SDK 事件并累积成本步内容。 */
        inner class StreamAccumulator(private val stepNumber: Int) {
            private val mark = clock.markNow()
            var started = false
            var firstOutputMs: Long? = null
            var finish: FinishReason? = null
            var rawFinish: String? = null
            var usage: neton.ai.protocol.usage.Usage? = null
            private inner class Building(val start: Block) {
                val text = StringBuilder()
                val args = StringBuilder()
                var signature: String? = (start as? ReasoningBlock)?.signature
                val citations = mutableListOf<JsonObject>().also { l -> (start as? TextBlock)?.citations?.let { l += it } }
                var stopMeta: ProviderMetadata = ProviderMetadata.EMPTY
                var done: List<AiContent>? = null
            }
            private val blocks = LinkedHashMap<Int, Building>()
            private var lastPartial: JsonElement? = null
            private val outputText = StringBuilder()

            private fun id(index: Int) = "s${stepNumber}_$index"

            private suspend fun output() {
                started = true
                emittedOutput = true
                if (firstOutputMs == null) firstOutputMs = mark.elapsedNow().inWholeMilliseconds
            }

            /** 返回这个事件是否是真正的输出（用于块间超时计时）。 */
            suspend fun accept(e: StreamEvent): Boolean {
                when (e) {
                    is StreamEvent.MessageStart -> started = true
                    is StreamEvent.BlockStart -> {
                        started = true
                        val b = Building(e.block)
                        blocks[e.index] = b
                        when (val s = e.block) {
                            is TextBlock -> { emit(AiStreamPart.TextStart(id(e.index))); if (s.text.isNotEmpty()) text(e.index, b, s.text) }
                            is ReasoningBlock -> { emit(AiStreamPart.ReasoningStart(id(e.index))); if (s.text.isNotEmpty()) reasoning(e.index, b, s.text) }
                            is ToolCallBlock -> {
                                emit(AiStreamPart.ToolInputStart(s.id, s.name))
                                if (s.arguments.isNotEmpty()) { b.args.append(s.arguments); emit(AiStreamPart.ToolInputDelta(s.id, s.arguments)); output(); return true }
                            }
                            is RefusalBlock -> b.text.append(s.text)
                            else -> {}
                        }
                    }
                    is StreamEvent.BlockDelta -> {
                        val b = blocks[e.index] ?: return false
                        when (val d = e.delta) {
                            is Delta.Text -> if (b.start is RefusalBlock) b.text.append(d.text) else { text(e.index, b, d.text); return true }
                            is Delta.Reasoning -> { reasoning(e.index, b, d.text); return true }
                            is Delta.ReasoningSummary -> { reasoning(e.index, b, d.text); return true }
                            is Delta.Signature -> b.signature = (b.signature ?: "") + d.signature
                            is Delta.Arguments -> {
                                b.args.append(d.json)
                                (b.start as? ToolCallBlock)?.let { emit(AiStreamPart.ToolInputDelta(it.id, d.json)) }
                                output(); return true
                            }
                            is Delta.Citation -> b.citations += d.citation
                        }
                    }
                    is StreamEvent.BlockStop -> {
                        val b = blocks[e.index] ?: return false
                        b.stopMeta = e.metadata
                        b.done = finalize(b)
                        when (val s = b.start) {
                            is TextBlock -> emit(AiStreamPart.TextEnd(id(e.index)))
                            is ReasoningBlock -> emit(AiStreamPart.ReasoningEnd(id(e.index)))
                            is ToolCallBlock -> {
                                emit(AiStreamPart.ToolInputEnd(s.id))
                                b.done = b.done!!.map { if (it is AiContent.ToolCall) validateCall(it, stepNumber) else it }
                                return true
                            }
                            is RefusalBlock -> { emit(AiStreamPart.Refusal(b.text.toString())); output(); return true }
                            else -> {}
                        }
                    }
                    is StreamEvent.UsageUpdate -> usage = usage?.mergedWith(e.usage) ?: e.usage
                    is StreamEvent.Finish -> { finish = e.reason; rawFinish = e.raw }
                    is StreamEvent.Error -> throw AiException(ModelMapping.toAiError(e.error, "stream"))
                }
                return false
            }

            private suspend fun text(index: Int, b: Building, t: String) {
                b.text.append(t)
                emit(AiStreamPart.TextDelta(id(index), t))
                output()
                val out = request.output ?: return
                if (out is AiOutput.Text) return
                outputText.append(t)
                val partial = out.parsePartial(outputText.toString())
                if (partial != null && partial != lastPartial) { lastPartial = partial; emit(AiStreamPart.PartialOutput(partial)) }
            }

            private suspend fun reasoning(index: Int, b: Building, t: String) {
                b.text.append(t)
                emit(AiStreamPart.ReasoningDelta(id(index), t))
                output()
            }

            private fun finalize(b: Building): List<AiContent> {
                val meta = mergeMeta(b.start.metadata, b.stopMeta)
                return when (val s = b.start) {
                    is TextBlock -> listOf(AiContent.Text(b.text.toString(), b.citations.toList(), meta))
                    is ReasoningBlock -> listOf(AiContent.Reasoning(b.text.toString(), b.signature?.takeIf { it.isNotEmpty() }, s.redactedData,
                        meta.string(ProviderMetadata.OPENAI, "encryptedContent") ?: s.encryptedContent, meta))
                    is RefusalBlock -> listOf(AiContent.Refusal(b.text.toString(), meta))
                    is ToolCallBlock -> listOf(AiContent.ToolCall(AiToolCall(s.id, s.name, b.args.toString().ifBlank { "{}" }), meta))
                    is HostedToolBlock -> listOf(AiContent.Opaque((meta[ProviderMetadata.OPENAI, "item"] as? JsonObject)?.let { s.copy(raw = it) } ?: s))
                    else -> ModelMapping.toContent(s)
                }
            }

            /** 上游没有显式关闭的块也收进来（截断的流）。空文本块丢弃。 */
            fun content(): List<AiContent> = blocks.values.flatMap { it.done ?: finalize(it) }
                .filter { !(it is AiContent.Text && it.text.isEmpty() && it.citations.isEmpty()) }
        }

        // ───────────────────────── 工具 ─────────────────────────

        inner class ToolPhase(
            val approvalRequests: List<AiContent.ToolApprovalRequest>,
            val approvalResponses: List<AiContent.ToolApprovalResponse>,
            val results: List<AiContent.ToolResult>,
            /** 有调用交还给调用方（审批挂起或没有执行器）。 */
            val pending: Boolean,
        )

        /** 校验工具调用：工具存在、参数合法；不合法时尝试修复，仍失败则标记 invalid（以错误结果交给模型）。 */
        suspend fun validateCall(c: AiContent.ToolCall, stepNumber: Int): AiContent.ToolCall {
            var call = c.call
            repeat(2) { attempt ->
                val tool = request.tools.firstOrNull { it.name == call.name }
                val error: AiToolCallError = when {
                    tool == null -> AiToolCallError.NoSuchTool(call.name, request.tools.map { it.name })
                    else -> {
                        val v = validateInput(tool, call)
                        if (v.isSuccess) {
                            val refined = v.getOrThrow()
                            val finalCall = if (refined == call.input) call else call.copy(argumentsJson = refined.toString())
                            return c.copy(call = finalCall).also { emit(AiStreamPart.ToolCall(it)) }
                        }
                        AiToolCallError.InvalidInput(call.name, call.argumentsJson, v.exceptionOrNull()?.message ?: "invalid input")
                    }
                }
                val repaired = if (attempt == 0) request.repairToolCall?.let { r ->
                    try { r.repair(call, error, request.tools, messages.toList()) } catch (e: CancellationException) { throw e } catch (e: Throwable) {
                        logSink?.invoke("ai.repairToolCall failed tool=${call.name}: ${e::class.simpleName}"); null
                    }
                } else null
                if (repaired == null) {
                    return c.copy(call = call, invalid = true).also {
                        invalidReasons[call.id] = error.message
                        emit(AiStreamPart.ToolCall(it))
                    }
                }
                call = repaired
            }
            error("unreachable")
        }

        private val invalidReasons = HashMap<String, String>()

        /** 解析、校验并改写参数。空参数视为 {}。 */
        fun validateInput(tool: AiTool, call: AiToolCall): Result<JsonElement> = runCatching {
            if (tool.kind is AiToolKind.Provider) return@runCatching call.input
            val parsed = try {
                Jsons.lenient.parseToJsonElement(call.argumentsJson.ifBlank { "{}" })
            } catch (e: Exception) {
                throw IllegalArgumentException("arguments are not valid JSON")
            }
            JsonSchemaValidator.validate(tool.inputSchema, parsed)?.let { throw IllegalArgumentException(it) }
            tool.refineInput?.invoke(parsed) ?: parsed
        }

        private suspend fun handleTools(content: List<AiContent>, stepNumber: Int, timings: MutableMap<String, Long>): ToolPhase {
            val requests = mutableListOf<AiContent.ToolApprovalRequest>()
            val responses = mutableListOf<AiContent.ToolApprovalResponse>()
            val results = mutableListOf<AiContent.ToolResult>()
            var pending = false
            val executable = mutableListOf<Triple<AiTool, JsonElement, AiToolCallContext>>()

            for (c in content.filterIsInstance<AiContent.ToolCall>().filter { !it.providerExecuted }) {
                val call = c.call
                if (c.invalid) {
                    results += AiContent.ToolResult(call.id, call.name, AiToolOutput.ErrorText(invalidReasons[call.id] ?: "invalid tool call"))
                    continue
                }
                val tool = request.tools.first { it.name == call.name }
                val input = call.input
                val ctx = context(call, input, stepNumber)

                val auth = request.authorizer?.authorize(tool, ctx)
                if (auth is AiApproval.Denied) { results += AiContent.ToolResult(call.id, call.name, AiToolOutput.Denied(auth.reason)); continue }

                val approval = request.toolApproval?.approve(tool, input, ctx)?.takeIf { it != AiApproval.NotApplicable }
                    ?: if (tool.needsApproval?.needsApproval(input, ctx) == true) AiApproval.UserApproval() else AiApproval.NotApplicable
                val approvalId = "approval_${call.id}"
                val signature = request.toolApprovalSecret?.let { sign(it, approvalId, call) }
                when (approval) {
                    is AiApproval.UserApproval -> {
                        requests += AiContent.ToolApprovalRequest(approvalId, call.id, approval.reason, signature = signature)
                        pending = true
                        continue
                    }
                    is AiApproval.Denied -> {
                        requests += AiContent.ToolApprovalRequest(approvalId, call.id, approval.reason, isAutomatic = true, signature = signature)
                        responses += AiContent.ToolApprovalResponse(approvalId, false, approval.reason)
                        results += AiContent.ToolResult(call.id, call.name, AiToolOutput.Denied(approval.reason))
                        continue
                    }
                    is AiApproval.Approved -> {
                        requests += AiContent.ToolApprovalRequest(approvalId, call.id, approval.reason, isAutomatic = true, signature = signature)
                        responses += AiContent.ToolApprovalResponse(approvalId, true, approval.reason)
                    }
                    AiApproval.NotApplicable -> {}
                }
                if (tool.execute == null && tool.toolSearch == null) { pending = true; continue }
                executable += Triple(tool, input, ctx)
            }
            requests.forEach { emit(AiStreamPart.ToolApprovalRequest(it)) }
            responses.forEach { emit(AiStreamPart.ToolApprovalResponse(it)) }
            results.forEach { emit(AiStreamPart.ToolResult(it)) }
            results += executeAll(executable, timings)

            // 结果按模型的调用顺序排列
            val order = content.filterIsInstance<AiContent.ToolCall>().map { it.call.id }
            results.sortBy { order.indexOf(it.toolCallId) }
            return ToolPhase(requests, responses, results, pending)
        }

        fun context(call: AiToolCall, input: JsonElement, stepNumber: Int) = AiToolCallContext(
            call = call, stepNumber = stepNumber, principal = request.principal, requestMetadata = request.metadata,
            messages = messages.toList(),
            onPreliminary = { out -> emit(AiStreamPart.ToolResult(AiContent.ToolResult(call.id, call.name, out, preliminary = true))) },
        )

        /** 执行：相邻的 parallelSafe 调用在并发上限内并行，其余串行；结果按输入顺序返回。 */
        suspend fun executeAll(items: List<Triple<AiTool, JsonElement, AiToolCallContext>>, timings: MutableMap<String, Long>): List<AiContent.ToolResult> {
            val out = arrayOfNulls<AiContent.ToolResult>(items.size)
            val gate = Semaphore(request.toolCallConcurrency)
            var i = 0
            while (i < items.size) {
                if (!items[i].first.parallelSafe || request.toolCallConcurrency == 1) {
                    out[i] = executeOne(items[i], timings); i++; continue
                }
                var j = i
                while (j < items.size && items[j].first.parallelSafe) j++
                val batch = (i until j).toList()
                val done = coroutineScope { batch.map { k -> async { gate.withPermit { executeOne(items[k], timings) } } }.awaitAll() }
                batch.forEachIndexed { n, k -> out[k] = done[n] }
                i = j
            }
            return out.map { it!! }
        }

        private suspend fun executeOne(item: Triple<AiTool, JsonElement, AiToolCallContext>, timings: MutableMap<String, Long>): AiContent.ToolResult {
            val (tool, input, ctx) = item
            val mark = clock.markNow()
            observe(logSink, "onToolExecutionStart") { callbacks?.onToolExecutionStart(ctx) }
            val output: AiToolOutput = try {
                val timeout = tool.timeoutMillis ?: request.timeout?.toolMs
                val block: suspend () -> AiToolOutput = {
                    if (tool.toolSearch != null) searchTools(tool.toolSearch, input) else tool.execute!!.execute(input, ctx)
                }
                if (timeout == null) block() else try {
                    withTimeout(timeout) { block() }
                } catch (e: TimeoutCancellationException) {
                    AiToolOutput.ErrorText("""{"error":"tool_timeout","message":"The tool did not finish within ${timeout}ms."}""")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                AiToolOutput.ErrorText(AiToolFailure.contentFor(tool.name, e, logSink))
            }
            val ms = mark.elapsedNow().inWholeMilliseconds
            timings[ctx.call.id] = ms
            observe(logSink, "onToolExecutionEnd") { callbacks?.onToolExecutionEnd(ctx, output, ms) }
            return AiContent.ToolResult(ctx.call.id, tool.name, output).also { emit(AiStreamPart.ToolResult(it)) }
        }

        /** 工具检索：在延迟加载的工具里查找，命中的从下一步起可用。 */
        private suspend fun searchTools(spec: AiToolSearch, input: JsonElement): AiToolOutput {
            val query = ((input as? JsonObject)?.get("query") as? JsonPrimitive)?.contentOrNull ?: ""
            val deferred = request.tools.filter { it.deferLoading }
            val ranked = spec.search?.invoke(query, deferred) ?: defaultRank(query, deferred)
            val hits = ranked.distinct().mapNotNull { n -> deferred.firstOrNull { it.name == n } }.take(spec.maxResults)
            discovered += hits.map { it.name }
            return AiToolOutput.Json(buildJsonObject {
                put("tools", buildJsonArray { hits.forEach { t -> add(buildJsonObject { put("name", t.name); t.description?.let { put("description", it) } }) } })
            })
        }

        // ───────────────────────── 结构化输出 ─────────────────────────

        private fun parseOutput(output: AiOutput<*>): AiOutputResult<Any?> {
            val step = steps.lastOrNull() ?: return AiOutputResult.NotGenerated("no step completed")
            step.content.filterIsInstance<AiContent.Refusal>().firstOrNull()?.let { return AiOutputResult.Refused(it.text) }
            if (step.finishReason == AiFinishReason.ContentFilter) return AiOutputResult.Refused(step.text.ifEmpty { "content filtered" })
            if (step.finishReason == AiFinishReason.Length) return AiOutputResult.Truncated(step.text)
            if (step.text.isEmpty() && step.toolCalls.isNotEmpty()) return AiOutputResult.NotGenerated("the last step ended with tool calls")
            return try {
                AiOutputResult.Success(output.parse(step.text))
            } catch (e: AiOutputException) {
                e.result
            }
        }

        private suspend fun recordUsage(modelId: AiModelId, step: Int, usage: AiUsage?, finish: AiFinishReason?, mark: TimeSource.Monotonic.ValueTimeMark, error: AiError?) {
            try {
                recorder.record(AiUsageEvent(
                    requestId = request.metadata["requestId"] ?: request.metadata["traceId"],
                    providerId = modelId.providerId, modelName = modelId.modelName,
                    usage = usage, round = step, requestMetadata = request.metadata,
                    timestampEpochMillis = kotlin.time.Clock.System.now().toEpochMilliseconds(),
                    durationMillis = mark.elapsedNow().inWholeMilliseconds,
                    finishReason = finish, success = error == null, errorKind = error?.kind,
                ))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logSink?.invoke("ai.usage recorder failed: ${e::class.simpleName}")
            }
        }
    }

    companion object {
        /** 审批签名：HMAC-SHA256(secret, JSON [approvalId, toolCallId, toolName, arguments])。 */
        fun sign(secret: String, approvalId: String, call: AiToolCall): String {
            val data = buildJsonArray {
                add(JsonPrimitive(approvalId)); add(JsonPrimitive(call.id)); add(JsonPrimitive(call.name)); add(call.input)
            }.toString()
            return neton.security.crypto.HmacSha256.signHex(secret.encodeToByteArray(), data.encodeToByteArray())
        }

        fun mergeProviderOptions(base: Map<String, JsonObject>, over: Map<String, JsonObject>?): Map<String, JsonObject> {
            if (over.isNullOrEmpty()) return base
            return (base.keys + over.keys).associateWith { k -> JsonObject((base[k] ?: JsonObject(emptyMap())) + (over[k] ?: JsonObject(emptyMap()))) }
        }

        fun mergeMeta(a: ProviderMetadata, b: ProviderMetadata): ProviderMetadata {
            var m = a
            for ((ns, obj) in b.namespaces) for ((k, v) in obj) m = m.with(ns, k, v)
            return m
        }

        /** 默认检索：名称的词命中记 2 分、描述命中记 1 分，去掉 0 分，按分数排序。 */
        fun defaultRank(query: String, tools: List<AiTool>): List<String> {
            val q = tokens(query)
            if (q.isEmpty()) return emptyList()
            return tools.map { t ->
                val name = tokens(t.name)
                val desc = tokens(t.description ?: "")
                t.name to q.sumOf { w -> (if (w in name) 2 else 0) + (if (w in desc) 1 else 0) }
            }.filter { it.second > 0 }.sortedByDescending { it.second }.map { it.first }
        }

        private fun tokens(s: String): Set<String> =
            s.replace(Regex("([a-z])([A-Z])"), "$1 $2").lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }.toSet()
    }
}

/** 观测回调：异常写日志后忽略，取消照常传播。 */
internal suspend fun observe(logSink: AiLogSink?, name: String, block: suspend () -> Unit) {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        logSink?.invoke("ai.callback $name failed: ${e::class.simpleName}")
    }
}
