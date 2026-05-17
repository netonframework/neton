package neton.ai

data class EmbeddingRequest(
    val model: AiModelId,                 // explicit; embedding has no policy/default routing in v0.1
    val input: List<String>,              // batch input strings
    val metadata: Map<String, String> = emptyMap(),
)
