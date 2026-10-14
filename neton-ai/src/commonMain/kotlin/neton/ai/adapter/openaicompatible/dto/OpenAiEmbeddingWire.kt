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

@kotlinx.serialization.Serializable
internal data class OpenAiUsage(
    @kotlinx.serialization.SerialName("prompt_tokens") val promptTokens: Int? = null,
    @kotlinx.serialization.SerialName("completion_tokens") val completionTokens: Int? = null,
    @kotlinx.serialization.SerialName("total_tokens") val totalTokens: Int? = null,
)
