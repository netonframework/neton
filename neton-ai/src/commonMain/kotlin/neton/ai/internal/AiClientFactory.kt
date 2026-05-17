package neton.ai.internal

import neton.ai.AiClient
import neton.ai.provider.ProviderRegistry
import neton.ai.routing.ModelRouter
import neton.ai.usage.AiUsageRecorder

internal object AiClientFactory {
    fun create(
        registry: ProviderRegistry,
        router: ModelRouter,
        recorder: AiUsageRecorder,
    ): AiClient = DefaultAiClient(registry, router, recorder)

    // createFromConfig(...) added in Task 17 once AiConfig exists.
}
