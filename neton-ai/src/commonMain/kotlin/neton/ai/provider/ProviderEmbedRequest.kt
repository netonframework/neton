package neton.ai.provider

data class ProviderEmbedRequest(
    val input: List<String>,
    val metadata: Map<String, String>,
)
