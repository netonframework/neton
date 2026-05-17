package neton.ai.provider

interface AiTextModel {
    val providerId: String
    val modelName: String
    suspend fun generate(request: ProviderCallRequest): ProviderCallResponse
}
