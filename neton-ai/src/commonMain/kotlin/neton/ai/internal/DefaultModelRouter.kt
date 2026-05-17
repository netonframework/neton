package neton.ai.internal

import neton.ai.AiError
import neton.ai.AiException
import neton.ai.AiModelId
import neton.ai.routing.ModelRouter
import neton.ai.routing.RoutingConfig

internal class DefaultModelRouter(private val config: RoutingConfig) : ModelRouter {
    override fun resolve(explicitModel: AiModelId?, modelPolicy: String?): List<AiModelId> {
        if (explicitModel != null) return listOf(explicitModel)
        if (modelPolicy != null) {
            val policy = config.policies[modelPolicy]
                ?: throw AiException(AiError.InvalidRequest("Unknown model policy '$modelPolicy'"))
            val candidates = policy.prefer + policy.fallback
            if (candidates.isEmpty()) {
                throw AiException(AiError.InvalidRequest("Policy '$modelPolicy' has no models"))
            }
            return candidates
        }
        val def = config.defaultModel
            ?: throw AiException(AiError.InvalidRequest("No model or modelPolicy specified, and no defaultModel configured"))
        return listOf(def)
    }
}
