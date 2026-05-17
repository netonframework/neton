package neton.ai.adapter.openaicompatible.dto

import kotlinx.serialization.Serializable

@Serializable
internal data class OpenAiEmbeddingRequest(
    val model: String,
    val input: List<String>,
)

@Serializable
internal data class OpenAiEmbeddingResponse(
    val data: List<OpenAiEmbeddingData>,
    val model: String? = null,
    val usage: OpenAiUsage? = null,
)

@Serializable
internal data class OpenAiEmbeddingData(
    val index: Int,
    val embedding: List<Float>,        // OpenAI returns array of floats
)
