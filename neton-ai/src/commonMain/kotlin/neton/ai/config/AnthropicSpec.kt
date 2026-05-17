// neton-ai/src/commonMain/kotlin/neton/ai/config/AnthropicSpec.kt
package neton.ai.config

class AnthropicSpec(override val id: String) : ProviderSpec {
    var baseUrl: String? = null
    var apiKey: String? = null
    var version: String? = null
    var beta: List<String>? = null
    var timeoutMillis: Long? = null
    var defaultHeaders: Map<String, String>? = null
}
