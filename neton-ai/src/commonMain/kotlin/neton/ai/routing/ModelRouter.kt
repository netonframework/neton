package neton.ai.routing

import neton.ai.AiModelId

@neton.ai.ExperimentalNetonAiApi
interface ModelRouter {
    fun resolve(explicitModel: AiModelId?, modelPolicy: String?): List<AiModelId>
}
