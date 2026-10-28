package neton.ai.routing

import neton.ai.AiModelId

@neton.ai.ExperimentalNetonAiApi
data class ModelPolicy(val prefer: List<AiModelId>, val fallback: List<AiModelId>)
