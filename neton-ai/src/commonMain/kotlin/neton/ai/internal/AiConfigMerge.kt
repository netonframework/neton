// neton-ai/src/commonMain/kotlin/neton/ai/internal/AiConfigMerge.kt
package neton.ai.internal

import neton.ai.AiConfig
import neton.ai.AiModelId
import neton.ai.config.AnthropicSpec
import neton.ai.config.OpenAiCompatibleSpec
import neton.ai.routing.ModelPolicy
import neton.ai.routing.RoutingConfig

/**
 * Merge a file-config map (from neton-core ConfigLoader) into an AiConfig that may already have
 * DSL-set fields. DSL takes precedence per spec §4.3.
 *
 * Schema:
 *   debug = bool
 *   [providers.<id>]
 *     type = "openAiCompatible" | "anthropic"
 *     baseUrl, apiKey, timeoutMillis, organization (openAiCompatible)
 *     baseUrl, apiKey, timeoutMillis, version, beta (anthropic)
 *   [routing]
 *     defaultModel = "provider:model"
 *   [routing.policies.<name>]
 *     prefer = ["provider:model", ...]
 *     fallback = ["provider:model", ...]
 */
internal fun AiConfig.applyFileMap(map: Map<String, Any?>) {
    (map["debug"] as? Boolean)?.let {
        // file 'debug' only sets DSL.debug if DSL.debug is false (default)
        if (!debug) debug = it
    }

    @Suppress("UNCHECKED_CAST")
    val fileProviders = map["providers"] as? Map<String, Any?> ?: emptyMap()
    for ((id, raw) in fileProviders) {
        @Suppress("UNCHECKED_CAST")
        val pm = raw as? Map<String, Any?> ?: continue
        val type = pm["type"] as? String ?: continue
        val existing = providers[id]
        when (type) {
            "openAiCompatible" -> {
                val spec = (existing as? OpenAiCompatibleSpec) ?: OpenAiCompatibleSpec(id).also { providers[id] = it }
                mergeOpenAiCompatible(spec, pm)
            }
            "anthropic" -> {
                val spec = (existing as? AnthropicSpec) ?: AnthropicSpec(id).also { providers[id] = it }
                mergeAnthropic(spec, pm)
            }
            else -> { /* skip unknown type; validate() will surface as missing config */ }
        }
    }

    @Suppress("UNCHECKED_CAST")
    val fileRouting = map["routing"] as? Map<String, Any?>
    if (fileRouting != null) {
        // Build a merged RoutingConfig: file values fill unset DSL fields
        val defaultModelFromFile = (fileRouting["defaultModel"] as? String)?.let(AiModelId::parse)
        val mergedDefault = routing.defaultModel ?: defaultModelFromFile

        @Suppress("UNCHECKED_CAST")
        val filePolicies = fileRouting["policies"] as? Map<String, Any?> ?: emptyMap()
        val mergedPolicies = routing.policies.toMutableMap()  // DSL policies preserved
        for ((name, raw) in filePolicies) {
            if (name in mergedPolicies) continue  // DSL wins for same policy name
            @Suppress("UNCHECKED_CAST")
            val pm = raw as? Map<String, Any?> ?: continue
            @Suppress("UNCHECKED_CAST")
            val prefer = (pm["prefer"] as? List<String>).orEmpty().map(AiModelId::parse)
            @Suppress("UNCHECKED_CAST")
            val fallback = (pm["fallback"] as? List<String>).orEmpty().map(AiModelId::parse)
            mergedPolicies[name] = ModelPolicy(prefer, fallback)
        }
        routing = RoutingConfig(defaultModel = mergedDefault, policies = mergedPolicies)
    }
}

private fun mergeOpenAiCompatible(spec: OpenAiCompatibleSpec, m: Map<String, Any?>) {
    if (spec.baseUrl == null) (m["baseUrl"] as? String)?.let { spec.baseUrl = it }
    if (spec.apiKey == null) (m["apiKey"] as? String)?.let { spec.apiKey = it }
    if (spec.organization == null) (m["organization"] as? String)?.let { spec.organization = it }
    if (spec.timeoutMillis == null) (m["timeoutMillis"] as? Number)?.toLong()?.let { spec.timeoutMillis = it }
    @Suppress("UNCHECKED_CAST")
    if (spec.defaultHeaders == null) (m["defaultHeaders"] as? Map<String, String>)?.let { spec.defaultHeaders = it }
}

private fun mergeAnthropic(spec: AnthropicSpec, m: Map<String, Any?>) {
    if (spec.baseUrl == null) (m["baseUrl"] as? String)?.let { spec.baseUrl = it }
    if (spec.apiKey == null) (m["apiKey"] as? String)?.let { spec.apiKey = it }
    if (spec.version == null) (m["version"] as? String)?.let { spec.version = it }
    @Suppress("UNCHECKED_CAST")
    if (spec.beta == null) (m["beta"] as? List<String>)?.let { spec.beta = it }
    if (spec.timeoutMillis == null) (m["timeoutMillis"] as? Number)?.toLong()?.let { spec.timeoutMillis = it }
    @Suppress("UNCHECKED_CAST")
    if (spec.defaultHeaders == null) (m["defaultHeaders"] as? Map<String, String>)?.let { spec.defaultHeaders = it }
}

/**
 * Resolve effective provider config — apply built-in defaults to any still-null fields.
 * Called by AiClientFactory after applyFileMap; returns a value-class snapshot used to construct providers.
 */
internal data class EffectiveOpenAiCompatibleConfig(
    val id: String, val baseUrl: String, val apiKey: String,
    val organization: String?, val timeoutMillis: Long, val defaultHeaders: Map<String, String>,
)

internal data class EffectiveAnthropicConfig(
    val id: String, val baseUrl: String, val apiKey: String, val version: String,
    val beta: List<String>, val timeoutMillis: Long, val defaultHeaders: Map<String, String>,
)

internal fun OpenAiCompatibleSpec.toEffective(): EffectiveOpenAiCompatibleConfig =
    EffectiveOpenAiCompatibleConfig(
        id = id,
        baseUrl = baseUrl ?: error("OpenAiCompat '$id': baseUrl missing (should have been caught by validate())"),
        apiKey = apiKey ?: error("OpenAiCompat '$id': apiKey missing"),
        organization = organization,
        timeoutMillis = timeoutMillis ?: 60_000L,
        defaultHeaders = defaultHeaders ?: emptyMap(),
    )

internal fun AnthropicSpec.toEffective(): EffectiveAnthropicConfig =
    EffectiveAnthropicConfig(
        id = id,
        baseUrl = baseUrl ?: "https://api.anthropic.com",
        apiKey = apiKey ?: error("Anthropic '$id': apiKey missing"),
        version = version ?: "2023-06-01",
        beta = beta ?: emptyList(),
        timeoutMillis = timeoutMillis ?: 60_000L,
        defaultHeaders = defaultHeaders ?: emptyMap(),
    )
