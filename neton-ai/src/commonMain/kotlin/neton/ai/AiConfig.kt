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
    var httpClient: neton.http.client.HttpClient? = null
    internal val providers = mutableMapOf<String, ProviderSpec>()
    internal var routing: RoutingConfig = RoutingConfig()
    internal var usage: UsageConfig = UsageConfig(recorder = null)
    var debug: Boolean = false
    var logSink: AiLogSink? = null
    internal val middleware = mutableMapOf<String, MutableList<neton.ai.model.AiModelMiddleware>>()
    internal val customProviders = mutableMapOf<String, neton.ai.provider.AiProvider>()

    /**
     * 给某个 provider 的语言模型套上中间件（如 MiniMax 的 `<think>` 抽取）；[providerId] 为 `*` 时作用于全部。
     * 先配置的先改写请求、最外层包住调用。
     */
    fun middleware(providerId: String, vararg middleware: neton.ai.model.AiModelMiddleware) {
        this.middleware.getOrPut(providerId) { mutableListOf() } += middleware
    }

    /** 注册自定义 provider（自建模型、测试替身等），与配置的 provider 一起参与路由。 */
    fun provider(provider: neton.ai.provider.AiProvider) {
        customProviders[provider.id] = provider
    }

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
        if (providers.isEmpty() && customProviders.isEmpty()) errors += "at least one provider must be configured"
        if (providers.isNotEmpty() && httpClient == null) errors += "httpClient is required"
        for ((id, spec) in providers) {
            when (spec) {
                is neton.ai.config.OpenAiCompatibleSpec -> {
                    if (spec.apiKey.isNullOrBlank()) errors += "provider '$id': apiKey is blank"
                    val url = spec.baseUrl
                    if (url.isNullOrBlank()) errors += "provider '$id': baseUrl is required"
                    else if (!url.startsWith("http://") && !url.startsWith("https://"))
                        errors += "provider '$id': baseUrl must start with http:// or https://"
                    spec.profile?.let { if (neton.ai.protocol.profile.ProviderProfiles.byName(it) == null) errors += "provider '$id': unknown profile '$it'" }
                    spec.api?.let { if (it != "chat" && it != "responses") errors += "provider '$id': api must be 'chat' or 'responses'" }
                }
                is neton.ai.config.AnthropicSpec -> {
                    if (spec.apiKey.isNullOrBlank()) errors += "provider '$id': apiKey is blank"
                }
                is neton.ai.config.GeminiSpec -> {
                    if (spec.apiKey.isNullOrBlank()) errors += "provider '$id': apiKey is blank"
                }
            }
        }
        routing.defaultModel?.let { def ->
            if (def.providerId !in providers && def.providerId !in customProviders) errors += "routing.defaultModel references unknown provider '${def.providerId}'"
        }
        for ((name, policy) in routing.policies) {
            if (policy.prefer.isEmpty()) errors += "policy '$name': prefer list is empty"
            for (m in policy.prefer + policy.fallback) {
                if (m.providerId !in providers && m.providerId !in customProviders) errors += "policy '$name': references unknown provider '${m.providerId}'"
            }
        }
        return errors
    }
}
