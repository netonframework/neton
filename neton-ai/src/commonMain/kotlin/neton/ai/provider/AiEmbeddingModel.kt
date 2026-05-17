package neton.ai.provider

interface AiEmbeddingModel {
    val providerId: String
    val modelName: String
    suspend fun embed(request: ProviderEmbedRequest): ProviderEmbedResponse
}
