// neton-ai/src/commonMain/kotlin/neton/ai/config/RoutingBuilder.kt
package neton.ai.config

import neton.ai.AiModelId
import neton.ai.routing.ModelPolicy
import neton.ai.routing.RoutingConfig

class RoutingBuilder internal constructor() {
    var defaultModel: String? = null
    private val policies = mutableMapOf<String, ModelPolicy>()

    fun policy(name: String, block: PolicyBuilder.() -> Unit) {
        require(name.isNotBlank()) { "Policy name must not be blank" }
        require(name !in policies) { "Duplicate policy '$name'" }
        policies[name] = PolicyBuilder().apply(block).build()
    }

    internal fun build(): RoutingConfig = RoutingConfig(
        defaultModel = defaultModel?.let(AiModelId::parse),
        policies = policies.toMap(),
    )
}
