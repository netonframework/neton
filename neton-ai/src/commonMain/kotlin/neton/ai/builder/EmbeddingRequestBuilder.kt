package neton.ai.builder

import neton.ai.AiModelId
import neton.ai.EmbeddingRequest

class EmbeddingRequestBuilder {
    var model: String? = null
    var metadata: Map<String, String> = emptyMap()
    private val inputs = mutableListOf<String>()

    fun input(text: String) { inputs += text }
    fun inputs(texts: Iterable<String>) { inputs.addAll(texts) }

    internal fun build(): EmbeddingRequest {
        val modelStr = model ?: throw IllegalStateException("embed requires explicit model (e.g., 'openai:text-embedding-3-small')")
        require(inputs.isNotEmpty()) { "embed requires at least one input" }
        return EmbeddingRequest(
            model = AiModelId.parse(modelStr),
            input = inputs.toList(),
            metadata = metadata,
        )
    }
}

@PublishedApi
internal fun EmbeddingRequestBuilder.toRequest(): EmbeddingRequest = build()
