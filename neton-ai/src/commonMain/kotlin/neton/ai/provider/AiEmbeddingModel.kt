package neton.ai.provider

/** PR3 will add: suspend fun embed(request: ProviderEmbedRequest): ProviderEmbedResponse */
interface AiEmbeddingModel {
    val providerId: String
    val modelName: String
}
