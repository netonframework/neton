package neton.ai

data class StreamTextRequest(
    val model: AiModelId? = null,
    val modelPolicy: String? = null,
    val messages: List<AiMessage>,
    val tools: List<AiToolDefinition> = emptyList(),
    val toolChoice: ToolChoice = ToolChoice.Auto,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val topP: Double? = null,
    val stopSequences: List<String> = emptyList(),
    val maxToolRounds: Int = 3,
    val metadata: Map<String, String> = emptyMap(),
    /** 推理意图（强度或预算），各协议按自己的写法发送（spec §4.3）。 */
    val reasoning: neton.ai.protocol.ir.ReasoningOptions? = null,
    /** 结构化输出：JSON 模式或 JSON Schema。 */
    val responseFormat: neton.ai.protocol.ir.ResponseFormat? = null,
    /**
     * 厂商专属参数，按 provider id 分组，原样合并进该 provider 的请求体；
     * 模型名、stream 与消息体等保留字段不会被覆盖。回退到其他 provider 时不会带过去。
     */
    val providerOptions: Map<String, kotlinx.serialization.json.JsonObject> = emptyMap(),
    /** 多步循环的控制、观测、工具授权与审批（spec §10.3）。 */
    val agent: AiAgentOptions = AiAgentOptions(),
)
