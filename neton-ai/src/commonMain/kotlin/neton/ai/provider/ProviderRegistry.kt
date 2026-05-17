package neton.ai.provider

interface ProviderRegistry {
    fun get(providerId: String): AiProvider?
    fun all(): Map<String, AiProvider>
}
