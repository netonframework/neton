package neton.ai.routing

import neton.ai.AiModelId

data class ModelPolicy(val prefer: List<AiModelId>, val fallback: List<AiModelId>)
