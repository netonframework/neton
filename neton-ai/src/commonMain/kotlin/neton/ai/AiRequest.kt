package neton.ai

import kotlinx.serialization.json.JsonObject

/**
 * 一次生成调用（`generate` 与 `stream` 共用同一个请求类型，spec/ai.md §10）。
 *
 * 多步循环：每一步调用一次模型；模型请求了本地工具时执行它们并进入下一步，直到
 * 没有待执行的工具、出现需要调用方处理的情况（审批、无执行器）、或 [stopWhen] 任一条件满足。
 */
@neton.ai.ExperimentalNetonAiApi
data class AiRequest(
    /** 显式模型；为空时按 [modelPolicy] 或默认模型路由。 */
    val model: AiModelId? = null,
    /** 路由策略名：按策略的首选与回退列表依次尝试（第一步失败且可回退时换下一个）。 */
    val modelPolicy: String? = null,
    /** 系统指令（独立于消息列表）。 */
    val instructions: String? = null,
    val messages: List<AiMessage>,
    val tools: List<AiTool> = emptyList(),
    val toolChoice: ToolChoice = ToolChoice.Auto,
    /** 只让模型看到这些工具（null = 全部）。不改变可执行的工具集合。 */
    val activeTools: Set<String>? = null,
    /** 工具发送顺序：列出的在前，其余按名称排序（稳定的顺序有利于厂商的提示缓存）。 */
    val toolOrder: List<String> = emptyList(),
    val settings: AiCallSettings = AiCallSettings(),
    /** 结构化输出声明；只解析**最后一步**的文本（见 [AiOutput]）。 */
    val output: AiOutput<*>? = null,
    /** 厂商专属参数，按 provider id 分组，只合并进该 provider 的请求体；保留字段不可覆盖。 */
    val providerOptions: Map<String, JsonObject> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
    val stopWhen: List<AiStopCondition> = listOf(AiStopCondition.stepCount(20)),
    val prepareStep: AiPrepareStep? = null,
    val toolApproval: AiToolApproval? = null,
    val authorizer: AiToolAuthorizer? = null,
    /** 配置后审批请求会带 HMAC 签名，答复时校验，防止客户端伪造或篡改待批准的调用。 */
    val toolApprovalSecret: String? = null,
    val repairToolCall: AiToolCallRepair? = null,
    /** 同时执行的 parallelSafe 工具调用上限。 */
    val toolCallConcurrency: Int = 8,
    val principal: AiPrincipal? = null,
    /** 模型调用失败时的重试次数（只重试可重试的错误，指数退避并遵守上游的 Retry-After）。 */
    val maxRetries: Int = 2,
    val timeout: AiTimeout? = null,
    val callbacks: AiCallbacks? = null,
    /** 透传给用量记录与 HTTP 客户端的元数据（requestId、traceId 等）；遥测会读取其中的 W3C `traceparent`。 */
    val metadata: Map<String, String> = emptyMap(),
    /** 遥测里标识这类调用的名字（如 `support-agent`），对应语义约定的 agent 名称。 */
    val functionId: String? = null,
) {
    init {
        require(maxRetries >= 0) { "maxRetries must be >= 0" }
        require(toolCallConcurrency >= 1) { "toolCallConcurrency must be >= 1" }
    }
}

/** 采样与长度等调用参数。 */
@neton.ai.ExperimentalNetonAiApi
data class AiCallSettings(
    val maxOutputTokens: Int? = null,
    val temperature: Double? = null,
    val topP: Double? = null,
    val topK: Int? = null,
    val presencePenalty: Double? = null,
    val frequencyPenalty: Double? = null,
    val stopSequences: List<String> = emptyList(),
    val seed: Long? = null,
    /** 推理强度；各协议按自己的写法发送（预算型协议按 maxOutputTokens 的比例换算）。 */
    val reasoning: AiReasoning? = null,
    /** 显式推理预算（token）；给出时优先于 [reasoning] 的档位换算。 */
    val reasoningBudgetTokens: Int? = null,
)

/** 推理档位（与主流厂商的取值对齐）。[ProviderDefault] 不发送任何推理参数。 */
@neton.ai.ExperimentalNetonAiApi
enum class AiReasoning(val effort: String?) {
    ProviderDefault(null), None("none"), Minimal("minimal"), Low("low"), Medium("medium"), High("high"), XHigh("xhigh"), Max("max")
}

/**
 * 超时（毫秒）。[firstChunkMs] 与 [chunkMs] 只对流式有效：前者从开始请求到第一个输出块，
 * 后者是相邻输出块的间隔（只有文本、推理、工具参数等真正的输出才重置计时）。
 *
 * [requestMs] 是**单次 HTTP 交换**（每次模型调用的每一次尝试）的传输层超时，覆盖 provider 配置的
 * `timeoutMillis`；为 null 时用 provider 的值（默认 60s）。它与应用共享的出站 HttpClient 的默认超时无关——
 * AI 调用总是带着自己的超时发出，长生成不必为此放宽整个应用的出站超时。
 */
@neton.ai.ExperimentalNetonAiApi
data class AiTimeout(
    val totalMs: Long? = null,
    val stepMs: Long? = null,
    val firstChunkMs: Long? = null,
    val chunkMs: Long? = null,
    val toolMs: Long? = null,
    val requestMs: Long? = null,
) {
    init {
        requestMs?.let { require(it > 0) { "requestMs must be > 0" } }
    }
}

/** 停止条件：在每一步结束、且循环本来会继续时检查，任一为 true 即停止。 */
@neton.ai.ExperimentalNetonAiApi
fun interface AiStopCondition {
    fun shouldStop(steps: List<AiStep>): Boolean

    companion object {
        /** 已执行到第 [count] 步。 */
        fun stepCount(count: Int) = AiStopCondition { it.size >= count }

        /** 最近一步调用了其中任一工具（常用于「调用 finish 工具即结束」）。 */
        fun hasToolCall(vararg names: String) = AiStopCondition { steps ->
            steps.lastOrNull()?.toolCalls?.any { it.name in names } == true
        }

        /** 累计输出 token 达到上限（用量未知的步不计）。 */
        fun outputTokensAtLeast(limit: Long) = AiStopCondition { steps ->
            steps.sumOf { it.usage.outputTokens ?: 0 } >= limit
        }
    }
}

@neton.ai.ExperimentalNetonAiApi
data class AiStepContext(
    val stepNumber: Int,
    val steps: List<AiStep>,
    val model: AiModelId,
    val instructions: String?,
    val messages: List<AiMessage>,
)

/**
 * prepareStep 的返回，只覆盖非 null 的项。
 * - 只作用于这一步：[model]、[toolChoice]、[activeTools]、[settings]、[providerOptions]（与请求级按命名空间合并）；
 * - 延续到后续步骤：[instructions]、[messages]（替换后成为之后的历史，如压缩上下文）。
 */
@neton.ai.ExperimentalNetonAiApi
data class AiStepOverrides(
    val model: AiModelId? = null,
    val toolChoice: ToolChoice? = null,
    val activeTools: Set<String>? = null,
    val instructions: String? = null,
    val messages: List<AiMessage>? = null,
    val settings: AiCallSettings? = null,
    val providerOptions: Map<String, JsonObject>? = null,
)

@neton.ai.ExperimentalNetonAiApi
fun interface AiPrepareStep {
    suspend fun prepare(context: AiStepContext): AiStepOverrides?
}

/**
 * 观测回调，默认都是空实现。回调只观察：抛出的异常写日志后忽略，不影响执行；取消照常传播。
 *
 * 顺序（每一步）：onStepStart → onModelCallStart → onModelCallEnd →（每个工具）onToolExecutionStart →
 * onToolExecutionEnd → onStepEnd；整次调用以 onEnd 结束（失败时 onError）。
 */
@neton.ai.ExperimentalNetonAiApi
interface AiCallbacks {
    suspend fun onStart(request: AiRequest) {}
    suspend fun onStepStart(context: AiStepContext) {}
    suspend fun onModelCallStart(stepNumber: Int, model: AiModelId) {}
    suspend fun onModelCallEnd(stepNumber: Int, model: AiModelId, usage: AiUsage, finishReason: AiFinishReason) {}
    suspend fun onToolExecutionStart(context: AiToolCallContext) {}
    suspend fun onToolExecutionEnd(context: AiToolCallContext, output: AiToolOutput, durationMs: Long) {}
    suspend fun onStepEnd(step: AiStep) {}
    suspend fun onEnd(result: AiResult) {}
    suspend fun onError(error: AiError) {}
}

/** 请求构建 DSL。 */
@neton.ai.ExperimentalNetonAiApi
class AiRequestBuilder {
    var model: String? = null
    var modelPolicy: String? = null
    var instructions: String? = null
    var toolChoice: ToolChoice = ToolChoice.Auto
    var activeTools: Set<String>? = null
    var toolOrder: List<String> = emptyList()
    var output: AiOutput<*>? = null
    var maxRetries: Int = 2
    var timeout: AiTimeout? = null
    var principal: AiPrincipal? = null
    var toolApproval: AiToolApproval? = null
    var authorizer: AiToolAuthorizer? = null
    var toolApprovalSecret: String? = null
    var repairToolCall: AiToolCallRepair? = null
    var toolCallConcurrency: Int = 8
    var prepareStep: AiPrepareStep? = null
    var callbacks: AiCallbacks? = null
    var metadata: Map<String, String> = emptyMap()
    var functionId: String? = null
    var headers: Map<String, String> = emptyMap()

    // 调用参数直接写在 DSL 顶层
    var maxOutputTokens: Int? = null
    var temperature: Double? = null
    var topP: Double? = null
    var topK: Int? = null
    var presencePenalty: Double? = null
    var frequencyPenalty: Double? = null
    var stopSequences: List<String> = emptyList()
    var seed: Long? = null
    var reasoning: AiReasoning? = null
    var reasoningBudgetTokens: Int? = null

    private val messages = mutableListOf<AiMessage>()
    private val tools = mutableListOf<AiTool>()
    private val stopWhen = mutableListOf<AiStopCondition>()
    private val providerOptions = mutableMapOf<String, JsonObject>()

    fun prompt(text: String) { messages += AiMessage.user(text) }
    fun user(text: String) { messages += AiMessage.user(text) }
    fun user(vararg content: AiContent) { messages += AiMessage.user(*content) }
    fun assistant(text: String) { messages += AiMessage.assistant(text) }
    fun message(m: AiMessage) { messages += m }
    fun messages(ms: Iterable<AiMessage>) { messages.addAll(ms) }

    fun tool(tool: AiTool) { tools += tool }
    fun tools(vararg t: AiTool) { tools.addAll(t) }
    fun tools(t: Iterable<AiTool>) { tools.addAll(t) }

    /** 文本函数工具的简写。 */
    fun tool(name: String, description: String, inputSchema: JsonObject = AiTool.EMPTY_OBJECT_SCHEMA,
             block: suspend (input: kotlinx.serialization.json.JsonElement, context: AiToolCallContext) -> String) {
        tools += AiTools.function(name, description, inputSchema, block = block)
    }

    /** 追加停止条件（设置后替换默认的 20 步上限，需要上限时一并写上 stepCount）。 */
    fun stopWhen(vararg conditions: AiStopCondition) { stopWhen.addAll(conditions) }

    fun providerOptions(providerId: String, options: JsonObject) { providerOptions[providerId] = options }

    fun build(): AiRequest = AiRequest(
        model = model?.let(AiModelId::parse),
        modelPolicy = modelPolicy,
        instructions = instructions,
        messages = messages.toList(),
        tools = tools.toList(),
        toolChoice = toolChoice,
        activeTools = activeTools,
        toolOrder = toolOrder,
        settings = AiCallSettings(maxOutputTokens, temperature, topP, topK, presencePenalty, frequencyPenalty, stopSequences, seed, reasoning, reasoningBudgetTokens),
        output = output,
        providerOptions = providerOptions.toMap(),
        headers = headers,
        stopWhen = stopWhen.toList().ifEmpty { listOf(AiStopCondition.stepCount(20)) },
        prepareStep = prepareStep,
        toolApproval = toolApproval,
        authorizer = authorizer,
        toolApprovalSecret = toolApprovalSecret,
        repairToolCall = repairToolCall,
        toolCallConcurrency = toolCallConcurrency,
        principal = principal,
        maxRetries = maxRetries,
        timeout = timeout,
        callbacks = callbacks,
        metadata = metadata,
        functionId = functionId,
    )
}

@neton.ai.ExperimentalNetonAiApi
fun aiRequest(block: AiRequestBuilder.() -> Unit): AiRequest = AiRequestBuilder().apply(block).build()
