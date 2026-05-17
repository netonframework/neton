package neton.ai

/**
 * "provider:model" identifier. Split at FIRST ':'; model name may contain '/', ':', '-', '.'.
 */
data class AiModelId(val providerId: String, val modelName: String) {
    override fun toString(): String = "$providerId:$modelName"

    companion object {
        fun parse(s: String): AiModelId {
            val idx = s.indexOf(':')
            require(idx > 0 && idx < s.length - 1) {
                "Invalid model id '$s', expected 'provider:model' (non-empty on both sides of first ':')"
            }
            return AiModelId(s.substring(0, idx), s.substring(idx + 1))
        }
    }
}
