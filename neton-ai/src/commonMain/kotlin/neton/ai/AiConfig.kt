// neton-ai/src/commonMain/kotlin/neton/ai/AiConfig.kt
package neton.ai

import neton.ai.config.ProviderSpec
import neton.ai.config.ProvidersBuilder
import neton.ai.config.RoutingBuilder
import neton.ai.config.UsageBuilder
import neton.ai.config.UsageConfig
import neton.ai.routing.RoutingConfig

/**
 * Root DSL config. Used by both standalone (AiClient.Companion.create) and component (AiComponent).
 *
 * `httpClient` field: required. Borrowed from the application; neton-ai never closes it.
 * and sets this field internally before the DSL block runs.
 */
class AiConfig {
    var httpClient: neton.http.client.NetonHttpClient? = null
    internal val providers = mutableMapOf<String, ProviderSpec>()
    internal var routing: RoutingConfig = RoutingConfig()
    internal var usage: UsageConfig = UsageConfig(recorder = null)
    var debug: Boolean = false
    var logSink: AiLogSink? = null

    fun providers(block: ProvidersBuilder.() -> Unit) {
        ProvidersBuilder(providers).apply(block)
    }

    fun routing(block: RoutingBuilder.() -> Unit) {
        routing = RoutingBuilder().apply(block).build()
    }

    fun usage(block: UsageBuilder.() -> Unit) {
        usage = UsageBuilder().apply(block).build()
    }

    /** Validation per spec §3.9. Returns list of error strings; empty = valid. */
    internal fun validate(): List<String> {
        val errors = mutableListOf<String>()
        if (httpClient == null) errors += "httpClient is required"
        if (providers.isEmpty()) errors += "at least one provider must be configured"
        for ((id, spec) in providers) {
            when (spec) {
                is neton.ai.config.OpenAiCompatibleSpec -> {
                    if (spec.apiKey.isNullOrBlank()) errors += "provider '$id': apiKey is blank"
                    val url = spec.baseUrl
                    if (url.isNullOrBlank()) errors += "provider '$id': baseUrl is required"
                    else if (!url.startsWith("http://") && !url.startsWith("https://"))
                        errors += "provider '$id': baseUrl must start with http:// or https://"
                }
                is neton.ai.config.AnthropicSpec -> {
                    if (spec.apiKey.isNullOrBlank()) errors += "provider '$id': apiKey is blank"
                }
            }
        }
        routing.defaultModel?.let { def ->
            if (def.providerId !in providers) errors += "routing.defaultModel references unknown provider '${def.providerId}'"
        }
        for ((name, policy) in routing.policies) {
            if (policy.prefer.isEmpty()) errors += "policy '$name': prefer list is empty"
            for (m in policy.prefer + policy.fallback) {
                if (m.providerId !in providers) errors += "policy '$name': references unknown provider '${m.providerId}'"
            }
        }
        return errors
    }
}
