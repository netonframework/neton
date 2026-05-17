package neton.ai.routing

import neton.ai.AiModelId

interface ModelRouter {
    fun resolve(explicitModel: AiModelId?, modelPolicy: String?): List<AiModelId>
}
