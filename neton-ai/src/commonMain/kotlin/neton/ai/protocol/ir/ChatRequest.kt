package neton.ai.protocol.ir

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@neton.ai.ExperimentalNetonAiApi
enum class Role { System, Developer, User, Assistant, Tool }

/** 系统指令（spec §4.2：独立字段，不混在消息列表里）。[role] 区分 OpenAI 的 system 与 developer。 */
@neton.ai.ExperimentalNetonAiApi
data class Instruction(
    val text: String,
    val role: Role = Role.System,
    val metadata: ProviderMetadata = ProviderMetadata.EMPTY,
)

/**
 * 消息。工具结果用 [ToolResultBlock] 表达：OpenAI Chat 解码为 role=Tool 的消息，Anthropic/Gemini 解码为
 * role=User 的消息，各协议编码时按自己的规则摆放。对话中途出现的 system/developer 消息保留为消息。
 */
@neton.ai.ExperimentalNetonAiApi
data class Message(
    val role: Role,
    val content: List<Block>,
    /** OpenAI 的 `name` 字段。 */
    val name: String? = null,
    val metadata: ProviderMetadata = ProviderMetadata.EMPTY,
)

@neton.ai.ExperimentalNetonAiApi
sealed interface ToolSpec {
    val metadata: ProviderMetadata
}

@neton.ai.ExperimentalNetonAiApi
data class FunctionTool(
    val name: String,
    val description: String? = null,
    /** JSON Schema；null 表示无参数。 */
    val parameters: JsonElement? = null,
    val strict: Boolean? = null,
    override val metadata: ProviderMetadata = ProviderMetadata.EMPTY,
) : ToolSpec

/** OpenAI custom tool（自由文本输入，可带语法约束）。 */
@neton.ai.ExperimentalNetonAiApi
data class CustomTool(
    val name: String,
    val description: String? = null,
    val format: JsonElement? = null,
    override val metadata: ProviderMetadata = ProviderMetadata.EMPTY,
) : ToolSpec

/** 厂商托管工具（web search、code execution 等），原样保留，只能写回 [sourceFormat]。 */
@neton.ai.ExperimentalNetonAiApi
data class HostedTool(
    val type: String,
    val raw: JsonObject,
    val sourceFormat: WireFormat,
    override val metadata: ProviderMetadata = ProviderMetadata.EMPTY,
) : ToolSpec

@neton.ai.ExperimentalNetonAiApi
sealed interface ToolChoice {
    data object Auto : ToolChoice
    data object None : ToolChoice
    data object Required : ToolChoice
    data class Named(val name: String) : ToolChoice
}

@neton.ai.ExperimentalNetonAiApi
sealed interface ResponseFormat {
    data object Text : ResponseFormat
    data object JsonObject : ResponseFormat
    data class JsonSchema(
        val name: String,
        val schema: JsonElement,
        val strict: Boolean? = null,
        val description: String? = null,
    ) : ResponseFormat
}

@neton.ai.ExperimentalNetonAiApi
enum class ReasoningMode { Disabled, Enabled, Adaptive }

/** 推理意图（spec §4.3）。[effort] 用 OpenAI 的取值：minimal/low/medium/high/xhigh 等，原样传递。 */
@neton.ai.ExperimentalNetonAiApi
data class ReasoningOptions(
    val mode: ReasoningMode? = null,
    val effort: String? = null,
    val budgetTokens: Int? = null,
    val includeThoughts: Boolean? = null,
)

/** 公共调用选项（spec §4.3）。 */
@neton.ai.ExperimentalNetonAiApi
data class CallOptions(
    val maxOutputTokens: Int? = null,
    val temperature: Double? = null,
    val topP: Double? = null,
    val topK: Int? = null,
    val stop: List<String> = emptyList(),
    val seed: Long? = null,
    val presencePenalty: Double? = null,
    val frequencyPenalty: Double? = null,
    val toolChoice: ToolChoice? = null,
    val parallelToolCalls: Boolean? = null,
    val responseFormat: ResponseFormat? = null,
    val reasoning: ReasoningOptions? = null,
    /** 终端用户标识（OpenAI `user`、Anthropic `metadata.user_id`）。 */
    val user: String? = null,
    /** 候选数（OpenAI `n`、Gemini `candidateCount`）。 */
    val candidateCount: Int? = null,
    /** 按厂商命名空间隔离的选项（spec §4.3）；回退到另一家时不转发。 */
    val providerOptions: Map<String, JsonObject> = emptyMap(),
)

@neton.ai.ExperimentalNetonAiApi
data class ChatRequest(
    val model: String? = null,
    val instructions: List<Instruction> = emptyList(),
    val messages: List<Message> = emptyList(),
    val tools: List<ToolSpec> = emptyList(),
    val options: CallOptions = CallOptions(),
    val stream: Boolean = false,
    /** 流式时是否请求用量（OpenAI `stream_options.include_usage`）；null = 未指定。 */
    val streamUsage: Boolean? = null,
    val metadata: ProviderMetadata = ProviderMetadata.EMPTY,
)
