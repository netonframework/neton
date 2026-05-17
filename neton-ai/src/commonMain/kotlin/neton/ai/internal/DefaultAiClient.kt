package neton.ai.internal

import kotlinx.coroutines.flow.Flow
import neton.ai.AiClient
import neton.ai.AiError
import neton.ai.AiException
import neton.ai.AiStreamEvent
import neton.ai.EmbeddingRequest
import neton.ai.EmbeddingResult
import neton.ai.GenerateTextRequest
import neton.ai.GenerateTextResult
import neton.ai.StreamTextRequest
import neton.ai.builder.EmbeddingRequestBuilder
import neton.ai.builder.GenerateTextRequestBuilder
import neton.ai.builder.StreamTextRequestBuilder
import neton.ai.builder.toRequest
import neton.ai.provider.ProviderEmbedRequest
import neton.ai.provider.ProviderRegistry
import neton.ai.routing.ModelRouter
import neton.ai.usage.AiUsageRecorder

internal class DefaultAiClient(
    private val registry: ProviderRegistry,
    private val router: ModelRouter,
    private val recorder: AiUsageRecorder,
) : AiClient {

    override suspend fun generateText(block: GenerateTextRequestBuilder.() -> Unit): GenerateTextResult {
        val request = GenerateTextRequestBuilder().apply(block).toRequest()
        return generateText(request)
    }

    override suspend fun generateText(request: GenerateTextRequest): GenerateTextResult =
        runToolLoop(request, registry, router, recorder)

    override fun streamText(block: StreamTextRequestBuilder.() -> Unit): Flow<AiStreamEvent> =
        streamText(StreamTextRequestBuilder().apply(block).toRequest())

    override fun streamText(request: StreamTextRequest): Flow<AiStreamEvent> =
        runStreamingToolLoop(request, registry, router, recorder)

    override suspend fun embed(block: EmbeddingRequestBuilder.() -> Unit): EmbeddingResult =
        embed(EmbeddingRequestBuilder().apply(block).toRequest())

    override suspend fun embed(request: EmbeddingRequest): EmbeddingResult {
        val provider = registry.get(request.model.providerId)
            ?: throw AiException(AiError.ModelNotFound(request.model.toString(), "Provider '${request.model.providerId}' not registered"))
        val model = provider.embeddingModel(request.model.modelName)
            ?: throw AiException(AiError.ModelNotFound(request.model.toString(), "Provider '${request.model.providerId}' does not support embeddings (model '${request.model.modelName}')"))
        val resp = model.embed(ProviderEmbedRequest(input = request.input, metadata = request.metadata))
        return EmbeddingResult(
            embeddings = resp.embeddings,
            usage = resp.usage,
            providerId = request.model.providerId,
            modelName = request.model.modelName,
        )
    }

    override suspend fun close() {
        // DefaultAiClient 不拥有 NetonHttpClient；外层（standalone factory / AiComponent）负责管理生命周期
    }
}
