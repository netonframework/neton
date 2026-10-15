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
        logSink: neton.ai.AiLogSink? = null,
        telemetry: TelemetryHub = TelemetryHub.NONE,
    ): AiClient = DefaultAiClient(registry, router, recorder, logSink, telemetry)

    /**
     * High-level: construct from an AiConfig that has already been merged with file values.
     * Validates the config; throws AiException(InvalidRequest) on errors.
     */
    fun createFromConfig(config: AiConfig): AiClient {
        val errors = config.validate()
        if (errors.isNotEmpty()) {
            throw AiException(AiError.InvalidRequest("Invalid AI config: ${errors.joinToString()}"))
        }
        val httpClient = config.httpClient

        val configured: Map<String, AiProvider> = config.providers.mapValues { (_, spec) ->
            httpClient ?: error("validate() should have caught null httpClient")
            when (spec) {
                is OpenAiCompatibleSpec -> {
                    val eff = spec.toEffective()
                    OpenAiCompatibleProvider(
                        id = eff.id, httpClient = httpClient,
                        baseUrl = eff.baseUrl, apiKey = eff.apiKey,
                        organization = eff.organization, defaultHeaders = eff.defaultHeaders,
                        logSink = config.logSink, debug = config.debug,
                        streamUsage = spec.streamUsage,
                        profile = spec.profile?.let { neton.ai.protocol.profile.ProviderProfiles.byName(it) },
                        api = if (spec.api == "responses") neton.ai.adapter.openaicompatible.OpenAiApi.Responses else neton.ai.adapter.openaicompatible.OpenAiApi.Chat,
                    )
                }
                is neton.ai.config.GeminiSpec -> neton.ai.adapter.gemini.GeminiProvider(
                    id = spec.id, httpClient = httpClient,
                    baseUrl = spec.baseUrl ?: "https://generativelanguage.googleapis.com",
                    apiKey = spec.apiKey ?: error("Gemini '${spec.id}': apiKey missing"),
                    defaultHeaders = spec.defaultHeaders ?: emptyMap(),
                    logSink = config.logSink, debug = config.debug,
                )
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

        val all = configured + config.customProviders
        val providers = all.mapValues { (id, p) ->
            val mw = config.middleware["*"].orEmpty() + config.middleware[id].orEmpty()
            if (mw.isEmpty()) p else MiddlewareProvider(p, mw)
        }
        return create(
            registry = DefaultProviderRegistry(providers),
            router = DefaultModelRouter(config.routing),
            recorder = config.usage.recorder ?: NoopAiUsageRecorder,
            logSink = config.logSink,
            telemetry = TelemetryHub(config.telemetry.toList(), config.telemetrySettings, config.logSink),
        )
    }
}
