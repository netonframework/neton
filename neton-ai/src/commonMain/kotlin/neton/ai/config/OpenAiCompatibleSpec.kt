// neton-ai/src/commonMain/kotlin/neton/ai/config/OpenAiCompatibleSpec.kt
package neton.ai.config

/**
 * All fields nullable to distinguish "not set in DSL" from "set to default value", per spec §4.2.
 * Effective config applies defaults after file merge (see Task 16).
 */
class OpenAiCompatibleSpec(override val id: String) : ProviderSpec {
    var baseUrl: String? = null
    var apiKey: String? = null
    var organization: String? = null
    var timeoutMillis: Long? = null
    var defaultHeaders: Map<String, String>? = null
}
