package neton.ai.config

@neton.ai.ExperimentalNetonAiApi
class GeminiSpec(override val id: String) : ProviderSpec {
    var baseUrl: String? = null
    var apiKey: String? = null
    var timeoutMillis: Long? = null
    var defaultHeaders: Map<String, String>? = null
}
