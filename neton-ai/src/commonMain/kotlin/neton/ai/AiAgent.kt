package neton.ai

/**
 * 多步工具循环的控制与观测（spec/ai.md §10.3）。
 *
 * 执行顺序（每一步）：[AiPrepareStep] → [AiAgentCallbacks.onStepStart] → 调用模型 →
 * 对每个工具调用依次做授权（[AiToolAuthorizer]）与审批（[AiToolApprover]）→
 * [AiAgentCallbacks.onToolCallStart] → 执行 → [AiAgentCallbacks.onToolCallFinish] →
 * [AiAgentCallbacks.onStepFinish] → 检查 [AiStopCondition]。
 *
 * - **观测回调与授权拦截是两种机制**：回调只观察，抛出的异常写日志后忽略，不影响执行；
 *   授权、审批与 prepareStep 是控制逻辑，异常照常抛出、终止本次调用。取消（CancellationException）一律传播。
 * - **授权**回答「这个主体能不能用这个工具」；**审批**回答「是否同意这一次具体的调用」。
 */
data class AiAgentOptions(
    /** 任一条件满足即停止（在 maxToolRounds 之外）；在每一步执行完工具后检查。 */
    val stopWhen: List<AiStopCondition> = emptyList(),
    val prepareStep: AiPrepareStep? = null,
    val callbacks: AiAgentCallbacks? = null,
    val authorizer: AiToolAuthorizer? = null,
    val approver: AiToolApprover? = null,
    /** 发起本次调用的主体，传给授权、审批与工具执行。 */
    val principal: AiPrincipal? = null,
    /**
     * 同时执行的工具调用上限。默认 1（串行）；只有声明了 [AiToolDefinition.parallelSafe] 的相邻调用才会并行。
     * 取消不能撤销已经发生的副作用：并行执行的工具需要自行保证幂等。
     */
    val maxParallelTools: Int = 1,
)

/** 一步：一次模型调用，以及它触发的工具执行。 */
data class AiStep(
    /** 从 1 开始。 */
    val index: Int,
    val providerId: String,
    val modelName: String,
    val message: AiMessage,
    val toolCalls: List<AiToolCall>,
    val toolResults: List<AiToolResult>,
    val usage: AiUsage?,
    val finishReason: AiFinishReason,
)

fun interface AiStopCondition {
    fun shouldStop(steps: List<AiStep>): Boolean

    companion object {
        /** 已经执行了 [count] 步。 */
        fun stepCountIs(count: Int) = AiStopCondition { it.size >= count }

        /** 最近一步调用了其中任一工具（常用于「调用 finish 工具即结束」）。 */
        fun hasToolCall(vararg names: String) = AiStopCondition { steps ->
            steps.lastOrNull()?.toolCalls?.any { it.name in names } == true
        }
    }
}

data class AiStepContext(
    val stepIndex: Int,
    val steps: List<AiStep>,
    val messages: List<AiMessage>,
    val model: AiModelId,
)

/** prepareStep 的返回：只覆盖非 null 的项，且只作用于这一步。 */
data class AiStepOverrides(
    val model: AiModelId? = null,
    val tools: List<AiToolDefinition>? = null,
    val toolChoice: ToolChoice? = null,
    /** 替换发给模型的消息（如压缩历史）；会成为后续步骤的历史。 */
    val messages: List<AiMessage>? = null,
)

fun interface AiPrepareStep {
    suspend fun prepare(context: AiStepContext): AiStepOverrides?
}

data class AiPrincipal(val id: String, val attributes: Map<String, String> = emptyMap())

/** 工具执行时可见的上下文。 */
data class AiToolCallContext(
    val call: AiToolCall,
    val stepIndex: Int,
    val principal: AiPrincipal?,
    val requestMetadata: Map<String, String>,
    val messages: List<AiMessage>,
)

sealed interface AiToolDecision {
    data object Allow : AiToolDecision

    /** [reason] 会作为工具结果交给模型，必须是可以公开的文本。 */
    data class Deny(val reason: String) : AiToolDecision

    /** 需要异步审批（如等人工确认）：本步不执行任何工具，把调用交还调用方。只对审批有意义。 */
    data object Pending : AiToolDecision
}

fun interface AiToolAuthorizer {
    suspend fun authorize(context: AiToolCallContext): AiToolDecision
}

fun interface AiToolApprover {
    suspend fun approve(context: AiToolCallContext): AiToolDecision
}

/** 观测回调，默认都是空实现。 */
interface AiAgentCallbacks {
    suspend fun onStepStart(context: AiStepContext) {}
    suspend fun onStepFinish(step: AiStep) {}
    suspend fun onToolCallStart(context: AiToolCallContext) {}
    suspend fun onToolCallFinish(context: AiToolCallContext, result: AiToolResult) {}
}

/** 需要上下文（主体、请求信息、历史）的工具实现。 */
fun interface AiToolHandler {
    suspend fun execute(context: AiToolCallContext): String
}
