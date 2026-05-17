package neton.ai.internal

import neton.ai.AiClient
import neton.ai.AiConfig
import neton.ai.AiError
import neton.ai.AiException
import neton.ai.adapter.anthropic.AnthropicProvider
import neton.ai.adapter.openaicompatible.OpenAiCompatibleProvider
import neton.ai.config.AnthropicSpec
import neton.ai.config.OpenAiCompatibleSpec
import neton.ai.provider.AiProvider
import neton.ai.provider.ProviderRegistry
import neton.ai.routing.ModelRouter
import neton.ai.usage.AiUsageRecorder
import neton.ai.usage.NoopAiUsageRecorder

internal object AiClientFactory {
    fun create(
        registry: ProviderRegistry,
        router: ModelRouter,
        recorder: AiUsageRecorder,
    ): AiClient = DefaultAiClient(registry, router, recorder)

    /**
     * High-level: construct from an AiConfig that has already been merged with file values.
     * Validates the config; throws AiException(InvalidRequest) on errors.
     */
    fun createFromConfig(config: AiConfig): AiClient {
        val errors = config.validate()
        if (errors.isNotEmpty()) {
            throw AiException(AiError.InvalidRequest("Invalid AI config: ${errors.joinToString()}"))
        }
        val httpClient = config.httpClient ?: error("validate() should have caught null httpClient")

        val providers: Map<String, AiProvider> = config.providers.mapValues { (_, spec) ->
            when (spec) {
                is OpenAiCompatibleSpec -> {
                    val eff = spec.toEffective()
                    OpenAiCompatibleProvider(
                        id = eff.id, httpClient = httpClient,
                        baseUrl = eff.baseUrl, apiKey = eff.apiKey,
                        organization = eff.organization, defaultHeaders = eff.defaultHeaders,
                        logSink = config.logSink, debug = config.debug,
                    )
                }
                is AnthropicSpec -> {
                    val eff = spec.toEffective()
                    AnthropicProvider(
                        id = eff.id, httpClient = httpClient,
                        baseUrl = eff.baseUrl, apiKey = eff.apiKey,
                        version = eff.version, beta = eff.beta,
                        defaultHeaders = eff.defaultHeaders,
                        logSink = config.logSink, debug = config.debug,
                    )
                }
            }
        }

        return create(
            registry = DefaultProviderRegistry(providers),
            router = DefaultModelRouter(config.routing),
            recorder = config.usage.recorder ?: NoopAiUsageRecorder,
        )
    }
}
