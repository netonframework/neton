package neton.ai.provider

@neton.ai.ExperimentalNetonAiApi
interface ProviderRegistry {
    fun get(providerId: String): AiProvider?
    fun all(): Map<String, AiProvider>
}
