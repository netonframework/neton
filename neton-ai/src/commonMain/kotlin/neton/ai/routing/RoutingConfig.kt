package neton.ai.routing

import neton.ai.AiModelId

data class RoutingConfig(
    val defaultModel: AiModelId? = null,
    val policies: Map<String, ModelPolicy> = emptyMap(),
)
