// neton-ai/src/commonMain/kotlin/neton/ai/config/PolicyBuilder.kt
package neton.ai.config

import neton.ai.AiModelId
import neton.ai.routing.ModelPolicy

class PolicyBuilder internal constructor() {
    private val prefer = mutableListOf<AiModelId>()
    private val fallback = mutableListOf<AiModelId>()
    fun prefer(modelId: String) { prefer += AiModelId.parse(modelId) }
    fun fallback(modelId: String) { fallback += AiModelId.parse(modelId) }
    internal fun build() = ModelPolicy(prefer.toList(), fallback.toList())
}
