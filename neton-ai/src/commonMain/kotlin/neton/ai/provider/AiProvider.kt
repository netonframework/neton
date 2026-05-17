package neton.ai.provider

interface AiProvider {
    val id: String
    fun textModel(modelName: String): AiTextModel?
    fun streamingTextModel(modelName: String): AiStreamingTextModel?
    fun embeddingModel(modelName: String): AiEmbeddingModel?
}
