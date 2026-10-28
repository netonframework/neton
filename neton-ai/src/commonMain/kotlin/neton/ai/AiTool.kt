package neton.ai

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import neton.ai.internal.Jsons

/**
 * 工具（spec/ai.md §10.3）。
 *
 * - [kind] = [AiToolKind.Function]：本地定义的函数工具，有 [execute] 时在循环里执行，没有时交还调用方；
 * - [AiToolKind.Dynamic]：运行时才知道 schema 的工具（如 MCP），结果按 JSON 处理；
 * - [AiToolKind.Provider]：厂商托管工具（web search、code execution 等），由上游执行，原样把参数交给厂商。
 */
@neton.ai.ExperimentalNetonAiApi
class AiTool(
    val name: String,
    val description: String? = null,
    /** 参数的 JSON Schema；调用前按它校验（见 [neton.ai.JsonSchemaValidator]）。 */
    val inputSchema: JsonObject = EMPTY_OBJECT_SCHEMA,
    /** 结果的 JSON Schema（没有执行器的工具用它告诉调用方该回什么）。 */
    val outputSchema: JsonObject? = null,
    /** 要求厂商严格按 schema 生成参数（OpenAI strict、Anthropic strict tool use）。 */
    val strict: Boolean? = null,
    /** 参数示例，帮助模型理解用法（支持的厂商原生发送，其余可用中间件拼进描述）。 */
    val inputExamples: List<JsonObject> = emptyList(),
    val kind: AiToolKind = AiToolKind.Function,
    /** 每次调用前要求批准（返回 true 即暂停等待调用方答复）。比 [AiRequest.toolApproval] 优先级低。 */
    val needsApproval: AiNeedsApproval? = null,
    /** 可以与相邻的其他 parallelSafe 调用并行执行（见 [AiRequest.toolCallConcurrency]）。 */
    val parallelSafe: Boolean = false,
    /** 单次执行超时；null 用 [AiTimeout.toolMs]。 */
    val timeoutMillis: Long? = null,
    /** 解析与校验通过后、执行前改写参数（规范化、补默认值）。改写后的参数用于执行、事件与历史。 */
    val refineInput: ((JsonElement) -> JsonElement)? = null,
    /** 默认不出现在可用工具里，只有被 [AiTools.toolSearch] 检索到后才在下一步可用（工具很多时省上下文）。 */
    val deferLoading: Boolean = false,
    val title: String? = null,
    val metadata: Map<String, String> = emptyMap(),
    /** 非空表示这是工具检索工具（见 [AiTools.toolSearch]），由循环执行。 */
    val toolSearch: AiToolSearch? = null,
    val execute: AiToolExecutor? = null,
) {
    override fun toString(): String = "AiTool($name, kind=$kind)"

    companion object {
        val EMPTY_OBJECT_SCHEMA: JsonObject = buildJsonObject { put("type", "object"); put("properties", buildJsonObject { }) }

        /** 厂商托管工具，如 `AiTool.provider("anthropic.web_search_20250305", "web_search", args)`。 */
        fun provider(id: String, name: String, args: JsonObject = JsonObject(emptyMap())) =
            AiTool(name, kind = AiToolKind.Provider(id, args))
    }
}

@neton.ai.ExperimentalNetonAiApi
sealed interface AiToolKind {
    data object Function : AiToolKind
    data object Dynamic : AiToolKind

    /** [id] 形如 `厂商.工具类型`（`anthropic.web_search_20250305`、`openai.web_search`、`google.google_search`）。 */
    data class Provider(val id: String, val args: JsonObject) : AiToolKind
}

/** 工具执行器：输入是校验（与改写）后的参数。 */
@neton.ai.ExperimentalNetonAiApi
fun interface AiToolExecutor {
    suspend fun execute(input: JsonElement, context: AiToolCallContext): AiToolOutput
}

@neton.ai.ExperimentalNetonAiApi
fun interface AiNeedsApproval {
    suspend fun needsApproval(input: JsonElement, context: AiToolCallContext): Boolean
}

/** 工具执行时可见的上下文。 */
@neton.ai.ExperimentalNetonAiApi
class AiToolCallContext(
    val call: AiToolCall,
    val stepNumber: Int,
    val principal: AiPrincipal?,
    val requestMetadata: Map<String, String>,
    /** 发给模型的历史（截至本步）。 */
    val messages: List<AiMessage>,
    private val onPreliminary: suspend (AiToolOutput) -> Unit = {},
) {
    /** 先行发出一个阶段性结果（只出现在流里，供界面展示进度；最终结果以执行器返回值为准）。 */
    suspend fun preliminary(output: AiToolOutput) = onPreliminary(output)
}

/**
 * 工具结果交给模型的形式。
 * - [Text] / [Json]：正常结果；
 * - [ErrorText] / [ErrorJson]：执行失败（模型据此调整）；
 * - [Denied]：没有执行（拒绝批准或未授权）；
 * - [Content]：多模态结果（文本 + 图片/文件），不支持的协议会降级为文本。
 */
@neton.ai.ExperimentalNetonAiApi
sealed interface AiToolOutput {
    val isError: Boolean get() = this is ErrorText || this is ErrorJson || this is Denied

    data class Text(val value: String) : AiToolOutput
    data class Json(val value: JsonElement) : AiToolOutput
    data class ErrorText(val value: String) : AiToolOutput
    data class ErrorJson(val value: JsonElement) : AiToolOutput
    data class Denied(val reason: String? = null) : AiToolOutput
    data class Content(val parts: List<AiContent>) : AiToolOutput

    /** 交给模型的文本形态（不支持富结果的协议用它）。 */
    fun asText(): String = when (this) {
        is Text -> value
        is ErrorText -> value
        is Json -> value.toString()
        is ErrorJson -> value.toString()
        is Denied -> buildJsonObject { put("error", "tool_execution_denied"); reason?.let { put("message", it) } }.toString()
        is Content -> parts.filterIsInstance<AiContent.Text>().joinToString("\n") { it.text }
    }
}

/** 常用工具构造。 */
@neton.ai.ExperimentalNetonAiApi
object AiTools {
    /** 返回文本的函数工具；执行器拿到原始 JSON 参数。 */
    fun function(
        name: String,
        description: String,
        inputSchema: JsonObject = AiTool.EMPTY_OBJECT_SCHEMA,
        parallelSafe: Boolean = false,
        block: suspend (input: JsonElement, context: AiToolCallContext) -> String,
    ) = AiTool(name, description, inputSchema, parallelSafe = parallelSafe,
        execute = AiToolExecutor { input, ctx -> AiToolOutput.Text(block(input, ctx)) })

    /**
     * 类型化工具：参数 schema 从 [I] 的序列化描述生成（[JsonSchemas] 支持的子集），参数按 [I] 解码，
     * 返回值按 [O] 编码为 JSON（[O] 是 String 时直接作为文本）。解码失败视为参数不合法，走修复流程。
     */
    inline fun <reified I, reified O> typed(
        name: String,
        description: String,
        strict: Boolean? = null,
        parallelSafe: Boolean = false,
        noinline block: suspend (input: I, context: AiToolCallContext) -> O,
    ): AiTool = typed(name, description, serializer<I>(), serializer<O>(), strict, parallelSafe, block)

    fun <I, O> typed(
        name: String,
        description: String,
        input: KSerializer<I>,
        output: KSerializer<O>,
        strict: Boolean? = null,
        parallelSafe: Boolean = false,
        block: suspend (input: I, context: AiToolCallContext) -> O,
    ): AiTool = AiTool(
        name = name,
        description = description,
        inputSchema = JsonSchemas.of(input, strict = strict == true),
        strict = strict,
        parallelSafe = parallelSafe,
        execute = AiToolExecutor { json, ctx ->
            val value = block(Jsons.strict.decodeFromJsonElement(input, json), ctx)
            if (value is String) AiToolOutput.Text(value) else AiToolOutput.Json(Jsons.strict.encodeToJsonElement(output, value))
        },
    )

    /** 动态工具（schema 运行时才知道，如 MCP），结果按 JSON 处理。 */
    fun dynamic(name: String, description: String?, inputSchema: JsonObject, execute: AiToolExecutor?) =
        AiTool(name, description, inputSchema, kind = AiToolKind.Dynamic, execute = execute)

    /**
     * 工具检索：模型调用它按关键词查找标了 [AiTool.deferLoading] 的工具，查到的工具在**下一步**可用。
     * 默认按名称（权重 2）与描述（权重 1）的词匹配排序；[search] 可替换为自定义检索（返回工具名，按相关度排序）。
     */
    fun toolSearch(maxResults: Int = 5, search: (suspend (query: String, tools: List<AiTool>) -> List<String>)? = null) =
        AiTool(
            name = TOOL_SEARCH_NAME,
            description = "Search for additional tools by keyword. Returns tool names you can call from the next step on.",
            inputSchema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject { put("query", buildJsonObject { put("type", "string") }) })
                put("required", kotlinx.serialization.json.buildJsonArray { add(JsonPrimitive("query")) })
            },
            toolSearch = AiToolSearch(maxResults, search),
        )

    const val TOOL_SEARCH_NAME = "tool_search"
}

/** 工具检索的配置。 */
@neton.ai.ExperimentalNetonAiApi
data class AiToolSearch(
    val maxResults: Int = 5,
    val search: (suspend (query: String, tools: List<AiTool>) -> List<String>)? = null,
)

// ───────────────────────── 授权与审批 ─────────────────────────

/**
 * 发起调用的主体。[identity] 接 Neton 安全体系的身份（neton-security 认证后得到），
 * 授权可以直接按其角色与权限判断（见 [AiToolAuthorizers]）。
 */
@neton.ai.ExperimentalNetonAiApi
data class AiPrincipal(
    val id: String,
    val attributes: Map<String, String> = emptyMap(),
    val identity: neton.core.interfaces.Identity? = null,
) {
    companion object {
        fun of(identity: neton.core.interfaces.Identity) = AiPrincipal(identity.id, identity = identity)
    }
}

/**
 * 审批状态（spec/ai.md §10.3）。授权回答「这个主体能不能用这个工具」，审批回答「是否同意这一次具体的调用」。
 */
@neton.ai.ExperimentalNetonAiApi
sealed interface AiApproval {
    /** 不需要审批，直接执行。 */
    data object NotApplicable : AiApproval
    /** 策略自动批准（结果里记录一对自动的审批请求与答复，便于审计）。 */
    data class Approved(val reason: String? = null) : AiApproval
    /** 策略自动拒绝：不执行，模型收到 [AiToolOutput.Denied]。[reason] 会交给模型，必须可以公开。 */
    data class Denied(val reason: String? = null) : AiApproval
    /** 需要调用方（通常是人）批准：本步暂停，结果里带审批请求，答复后再调用一次即可继续。 */
    data class UserApproval(val reason: String? = null) : AiApproval
}

/** 审批策略：对每个工具调用给出 [AiApproval]。 */
@neton.ai.ExperimentalNetonAiApi
fun interface AiToolApproval {
    suspend fun approve(tool: AiTool, input: JsonElement, context: AiToolCallContext): AiApproval

    companion object {
        /** 按工具名配置；没列出的工具不需要审批。 */
        fun perTool(rules: Map<String, AiApproval>) = AiToolApproval { tool, _, _ -> rules[tool.name] ?: AiApproval.NotApplicable }
    }
}

/** 授权：没有通过的调用不执行，模型收到 [AiToolOutput.Denied]。 */
@neton.ai.ExperimentalNetonAiApi
fun interface AiToolAuthorizer {
    suspend fun authorize(tool: AiTool, context: AiToolCallContext): AiApproval
}

/**
 * 基于 Neton 身份的常用授权（与 `@Permission` / `@RolesAllowed` 同一套字符串）。
 * 没有列出的工具放行；列出的工具要求主体具备对应权限或角色。
 */
@neton.ai.ExperimentalNetonAiApi
object AiToolAuthorizers {
    fun requirePermissions(byTool: Map<String, String>) = AiToolAuthorizer { tool, ctx ->
        val required = byTool[tool.name] ?: return@AiToolAuthorizer AiApproval.NotApplicable
        if (ctx.principal?.identity?.hasPermission(required) == true) AiApproval.NotApplicable
        else AiApproval.Denied("missing permission for tool '${tool.name}'")
    }

    fun requireRoles(byTool: Map<String, Set<String>>) = AiToolAuthorizer { tool, ctx ->
        val roles = byTool[tool.name] ?: return@AiToolAuthorizer AiApproval.NotApplicable
        if (ctx.principal?.identity?.hasAnyRole(*roles.toTypedArray()) == true) AiApproval.NotApplicable
        else AiApproval.Denied("missing role for tool '${tool.name}'")
    }

    /** 依次检查，第一个拒绝即拒绝。 */
    fun all(vararg authorizers: AiToolAuthorizer) = AiToolAuthorizer { tool, ctx ->
        for (a in authorizers) { val d = a.authorize(tool, ctx); if (d is AiApproval.Denied) return@AiToolAuthorizer d }
        AiApproval.NotApplicable
    }
}

/**
 * 工具调用修复：调用的工具不存在或参数不合法时调用。返回修复后的调用（只再校验一次），返回 null 表示不修复。
 * 修复失败的调用不会让整个请求失败：它以错误结果交给模型，由模型自己改正。
 */
@neton.ai.ExperimentalNetonAiApi
fun interface AiToolCallRepair {
    suspend fun repair(call: AiToolCall, error: AiToolCallError, tools: List<AiTool>, messages: List<AiMessage>): AiToolCall?
}

@neton.ai.ExperimentalNetonAiApi
sealed interface AiToolCallError {
    val message: String

    data class NoSuchTool(val toolName: String, val availableTools: List<String>) : AiToolCallError {
        override val message get() = "Model tried to call unavailable tool '$toolName'. Available tools: ${availableTools.joinToString()}."
    }

    data class InvalidInput(val toolName: String, val input: String, val reason: String) : AiToolCallError {
        override val message get() = "Invalid input for tool '$toolName': $reason"
    }
}
