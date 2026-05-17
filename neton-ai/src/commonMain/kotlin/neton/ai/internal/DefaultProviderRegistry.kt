// neton-ai/src/commonMain/kotlin/neton/ai/internal/DefaultProviderRegistry.kt
package neton.ai.internal

import neton.ai.provider.AiProvider
import neton.ai.provider.ProviderRegistry

internal class DefaultProviderRegistry(
    private val providers: Map<String, AiProvider>,
) : ProviderRegistry {
    override fun get(providerId: String): AiProvider? = providers[providerId]
    override fun all(): Map<String, AiProvider> = providers
}
