package neton.ai

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject
import neton.ai.model.AiLanguageModel
import neton.ai.model.AiWarning
import neton.ai.provider.GeneratedFile
import neton.ai.provider.RerankResult
import neton.ai.provider.SpeechOptions
import neton.ai.provider.SpeechResult
import neton.ai.provider.TranscriptionOptions
import neton.ai.provider.TranscriptionResult

/**
 * Lightweight log sink — a function that accepts a sanitized log line. Decoupled from
 * neton-logging so standalone usage doesn't pull in the logging runtime.
 */
@neton.ai.ExperimentalNetonAiApi
typealias AiLogSink = (line: String) -> Unit

/**
 * 与厂商无关的 AI 客户端（spec/ai.md §10）。
 *
 * 两种用法：独立使用 `AiClient.create { … }`；或作为 Neton 组件 `ai { … }`，下游从上下文取得。
 */
@neton.ai.ExperimentalNetonAiApi
interface AiClient {
    /** 生成（多步工具循环）。provider 错误在重试与回退后仍失败时抛 [AiException]。 */
    suspend fun generate(request: AiRequest): AiResult
    suspend fun generate(block: AiRequestBuilder.() -> Unit): AiResult = generate(aiRequest(block))

    /** 流式生成：以恰好一个 [AiStreamPart.Finish] 或 [AiStreamPart.Error] 结束。 */
    fun stream(request: AiRequest): Flow<AiStreamPart>
    fun stream(block: AiRequestBuilder.() -> Unit): Flow<AiStreamPart> = stream(aiRequest(block))

    /** 嵌入（自动按模型的单次上限切分、并发调用、逐批重试）。 */
    suspend fun embed(request: EmbeddingRequest): EmbeddingResult

    suspend fun generateImage(request: ImageRequest): ImageGenerationResult
    suspend fun generateSpeech(model: AiModelId, options: SpeechOptions, maxRetries: Int = 2): SpeechResult
    suspend fun transcribe(model: AiModelId, options: TranscriptionOptions, maxRetries: Int = 2): TranscriptionResult
    suspend fun rerank(model: AiModelId, query: String, documents: List<String>, topN: Int? = null, maxRetries: Int = 2): RerankResult

    /** 按 id 取已配置的语言模型（含配置的中间件），可直接调用或交给其他组件。 */
    fun languageModel(id: AiModelId): AiLanguageModel

    suspend fun close()

    companion object {
        /**
         * 独立使用：不需要 Neton 运行时。HTTP 客户端由调用方提供并负责关闭。
         * @throws AiException 配置不合法（InvalidRequest）。
         */
        fun create(block: AiConfig.() -> Unit): AiClient {
            val cfg = AiConfig().apply(block)
            return neton.ai.internal.AiClientFactory.createFromConfig(cfg)
        }
    }
}

@neton.ai.ExperimentalNetonAiApi
data class EmbeddingRequest(
    val model: AiModelId,
    val values: List<String>,
    val dimensions: Int? = null,
    /** 切分后的批次最多同时调用几批。 */
    val maxParallelCalls: Int = 4,
    val maxRetries: Int = 2,
    val providerOptions: Map<String, JsonObject> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
    val metadata: Map<String, String> = emptyMap(),
) {
    init {
        require(dimensions == null || dimensions > 0) { "dimensions must be positive" }
        require(maxParallelCalls >= 1) { "maxParallelCalls must be >= 1" }
    }
}

@neton.ai.ExperimentalNetonAiApi
class EmbeddingResult(
    /** 与 [EmbeddingRequest.values] 同序。 */
    val embeddings: List<FloatArray>,
    val usage: AiUsage,
    val warnings: List<AiWarning>,
    val model: AiModelId,
)

@neton.ai.ExperimentalNetonAiApi
data class ImageRequest(
    val model: AiModelId,
    val prompt: String,
    val n: Int = 1,
    val size: String? = null,
    val aspectRatio: String? = null,
    val seed: Long? = null,
    /** 单次调用最多几张；null 用模型的上限。超过时按批并发调用。 */
    val maxImagesPerCall: Int? = null,
    val maxRetries: Int = 2,
    val providerOptions: Map<String, JsonObject> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
)

@neton.ai.ExperimentalNetonAiApi
class ImageGenerationResult(val images: List<GeneratedFile>, val usage: AiUsage, val warnings: List<AiWarning>) {
    val image: GeneratedFile get() = images.first()
}
