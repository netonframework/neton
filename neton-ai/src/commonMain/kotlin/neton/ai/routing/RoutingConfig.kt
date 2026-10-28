package neton.ai.routing

import neton.ai.AiModelId

@neton.ai.ExperimentalNetonAiApi
data class RoutingConfig(
    val defaultModel: AiModelId? = null,
    val policies: Map<String, ModelPolicy> = emptyMap(),
)
