// neton-ai/src/commonMain/kotlin/neton/ai/config/ProvidersBuilder.kt
package neton.ai.config

class ProvidersBuilder internal constructor(internal val target: MutableMap<String, ProviderSpec>) {
    private val idPattern = Regex("[a-zA-Z0-9._-]+")

    fun openAiCompatible(id: String, block: OpenAiCompatibleSpec.() -> Unit) {
        require(idPattern.matches(id)) { "Invalid provider id '$id' (allowed: [a-zA-Z0-9._-]+)" }
        require(id !in target) { "Duplicate provider id '$id'" }
        target[id] = OpenAiCompatibleSpec(id).apply(block)
    }

    fun anthropic(id: String, block: AnthropicSpec.() -> Unit) {
        require(idPattern.matches(id)) { "Invalid provider id '$id'" }
        require(id !in target) { "Duplicate provider id '$id'" }
        target[id] = AnthropicSpec(id).apply(block)
    }
}
