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
    /**
     * 流式请求是否要求上游返回 usage（发送 `stream_options.include_usage`）。
     * null = 按 baseUrl 取内置厂商配置（[neton.ai.protocol.profile.ProviderProfiles]），未收录的厂商默认不发。
     */
    var streamUsage: Boolean? = null
}
