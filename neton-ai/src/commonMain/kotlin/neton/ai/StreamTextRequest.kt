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
)
