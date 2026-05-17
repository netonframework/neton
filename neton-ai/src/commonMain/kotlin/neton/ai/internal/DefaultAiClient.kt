package neton.ai.internal

import neton.ai.AiClient
import neton.ai.GenerateTextRequest
import neton.ai.GenerateTextResult
import neton.ai.builder.GenerateTextRequestBuilder
import neton.ai.builder.toRequest
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

    override suspend fun close() {
        // DefaultAiClient 不拥有 NetonHttpClient；外层（standalone factory / AiComponent）负责管理生命周期
    }
}
