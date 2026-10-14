package neton.ai.internal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import neton.ai.AiAgentOptions
import neton.ai.AiContent
import neton.ai.AiLogSink
import neton.ai.AiMessage
import neton.ai.AiRole
import neton.ai.AiToolCall
import neton.ai.AiToolCallContext
import neton.ai.AiToolDecision
import neton.ai.AiToolDefinition
import neton.ai.AiToolFailure
import neton.ai.AiToolResult

/** 一步的工具执行结果。 */
internal sealed interface StepTools {
    /** 全部执行完（含被拒绝的调用，它们以错误结果交给模型）。 */
    data class Executed(val results: List<AiToolResult>) : StepTools

    /** 交还调用方：有调用没有本地执行器，或审批挂起。本步没有执行任何工具。 */
    data class HandedBack(val calls: List<AiToolCall>) : StepTools
}

/**
 * 执行一步里的工具调用（spec §10.3）：先对所有调用做授权与审批，再执行；
 * 相邻的 parallelSafe 调用在 [AiAgentOptions.maxParallelTools] 内并行，结果按调用顺序返回。
 */
internal suspend fun executeStepTools(
    calls: List<AiToolCall>,
    tools: List<AiToolDefinition>,
    agent: AiAgentOptions,
    stepIndex: Int,
    requestMetadata: Map<String, String>,
    messages: List<AiMessage>,
    logSink: AiLogSink?,
    onResult: suspend (AiToolResult) -> Unit = {},
): StepTools {
    fun defOf(call: AiToolCall) = tools.firstOrNull { it.name == call.name }
    if (calls.any { c -> defOf(c).let { it == null || (it.executor == null && it.handler == null) } }) {
        return StepTools.HandedBack(calls)
    }
    val contexts = calls.map { AiToolCallContext(it, stepIndex, agent.principal, requestMetadata, messages) }

    // 控制逻辑：授权 → 审批。拒绝的调用直接得到错误结果；任一挂起则整步交还调用方。
    val denied = HashMap<Int, AiToolResult>()
    var pending = false
    for ((i, ctx) in contexts.withIndex()) {
        val auth = agent.authorizer?.authorize(ctx) ?: AiToolDecision.Allow
        if (auth is AiToolDecision.Deny) { denied[i] = refusal(ctx.call, "tool_not_authorized", auth.reason); continue }
        when (val approval = agent.approver?.approve(ctx) ?: AiToolDecision.Allow) {
            is AiToolDecision.Deny -> denied[i] = refusal(ctx.call, "tool_call_rejected", approval.reason)
            AiToolDecision.Pending -> pending = true
            AiToolDecision.Allow -> {}
        }
    }
    if (pending) return StepTools.HandedBack(calls)

    val results = arrayOfNulls<AiToolResult>(calls.size)
    val gate = Semaphore(agent.maxParallelTools.coerceAtLeast(1))
    suspend fun run(i: Int): AiToolResult {
        denied[i]?.let { return it }
        val ctx = contexts[i]
        val def = defOf(ctx.call)!!
        observe(logSink, "onToolCallStart") { agent.callbacks?.onToolCallStart(ctx) }
        val result = try {
            val out = def.handler?.execute(ctx) ?: def.executor!!.execute(ctx.call.argumentsJson)
            AiToolResult(ctx.call.id, out, isError = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            AiToolResult(ctx.call.id, AiToolFailure.contentFor(ctx.call.name, e, logSink), isError = true)
        }
        observe(logSink, "onToolCallFinish") { agent.callbacks?.onToolCallFinish(ctx, result) }
        return result
    }

    var i = 0
    while (i < calls.size) {
        val parallel = agent.maxParallelTools > 1 && defOf(calls[i])!!.parallelSafe
        if (!parallel) {
            results[i] = run(i).also { onResult(it) }
            i++
            continue
        }
        var j = i
        while (j < calls.size && defOf(calls[j])!!.parallelSafe) j++
        val batch = (i until j).toList()
        val done = coroutineScope { batch.map { k -> async { gate.withPermit { run(k) } } }.awaitAll() }
        batch.forEachIndexed { n, k -> results[k] = done[n]; onResult(done[n]) }
        i = j
    }
    return StepTools.Executed(results.map { it!! })
}

private fun refusal(call: AiToolCall, code: String, reason: String) = AiToolResult(
    call.id,
    buildJsonObject { put("error", code); put("message", reason) }.toString(),
    isError = true,
)

internal fun toolResultMessage(result: AiToolResult) = AiMessage(
    role = AiRole.Tool,
    content = listOf(AiContent.Text(result.content)),
    toolCallId = result.toolCallId,
    toolResultIsError = result.isError,
)

/** 观测回调：异常写日志后忽略，取消照常传播。 */
internal suspend fun observe(logSink: AiLogSink?, name: String, block: suspend () -> Unit) {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        logSink?.invoke("ai.agent callback $name failed: ${e::class.simpleName}")
    }
}
