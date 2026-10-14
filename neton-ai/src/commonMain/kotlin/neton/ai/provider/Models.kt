package neton.ai.provider

import kotlinx.serialization.json.JsonObject
import neton.ai.AiUsage
import neton.ai.model.AiWarning

/** 嵌入模型。 */
interface AiEmbeddingModel {
    val provider: String
    val modelId: String
    /** 单次调用最多的条数（null = 不限）；批量嵌入按它切分。 */
    val maxEmbeddingsPerCall: Int? get() = 2048
    /** 是否可以并发调用（切分后的批次）。 */
    val supportsParallelCalls: Boolean get() = true

    suspend fun embed(values: List<String>, options: EmbedOptions = EmbedOptions()): EmbedResult
}

data class EmbedOptions(
    val dimensions: Int? = null,
    val headers: Map<String, String> = emptyMap(),
    val providerOptions: Map<String, JsonObject> = emptyMap(),
    val metadata: Map<String, String> = emptyMap(),
)

class EmbedResult(val embeddings: List<FloatArray>, val usage: AiUsage, val warnings: List<AiWarning> = emptyList())

/** 图片生成模型。 */
interface AiImageModel {
    val provider: String
    val modelId: String
    val maxImagesPerCall: Int get() = 1
    suspend fun generate(options: ImageOptions): ImageResult
}

data class ImageOptions(
    val prompt: String,
    val n: Int = 1,
    /** `宽x高`，如 1024x1024。 */
    val size: String? = null,
    /** `宽:高`，如 16:9（支持它的厂商）。 */
    val aspectRatio: String? = null,
    val seed: Long? = null,
    val providerOptions: Map<String, JsonObject> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
)

/** [images] 为原始字节；[mediaType] 是厂商声明或嗅探出的类型。 */
class GeneratedFile(val bytes: ByteArray, val mediaType: String)

class ImageResult(val images: List<GeneratedFile>, val usage: AiUsage = AiUsage.UNKNOWN, val warnings: List<AiWarning> = emptyList())

/** 语音合成模型。 */
interface AiSpeechModel {
    val provider: String
    val modelId: String
    suspend fun generate(options: SpeechOptions): SpeechResult
}

data class SpeechOptions(
    val text: String,
    val voice: String? = null,
    /** mp3 / wav / opus / pcm 等。 */
    val outputFormat: String? = null,
    val instructions: String? = null,
    val speed: Double? = null,
    val providerOptions: Map<String, JsonObject> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
)

class SpeechResult(val audio: GeneratedFile, val warnings: List<AiWarning> = emptyList())

/** 语音识别模型。 */
interface AiTranscriptionModel {
    val provider: String
    val modelId: String
    suspend fun transcribe(options: TranscriptionOptions): TranscriptionResult
}

data class TranscriptionOptions(
    val audio: ByteArray,
    val mediaType: String,
    val language: String? = null,
    val prompt: String? = null,
    val providerOptions: Map<String, JsonObject> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
)

data class TranscriptSegment(val text: String, val startSecond: Double, val endSecond: Double)

class TranscriptionResult(
    val text: String,
    val segments: List<TranscriptSegment> = emptyList(),
    val language: String? = null,
    val durationInSeconds: Double? = null,
    val usage: AiUsage = AiUsage.UNKNOWN,
    val warnings: List<AiWarning> = emptyList(),
)

/** 重排序模型。 */
interface AiRerankingModel {
    val provider: String
    val modelId: String
    suspend fun rerank(query: String, documents: List<String>, topN: Int? = null, headers: Map<String, String> = emptyMap()): RerankResult
}

data class RankedDocument(val index: Int, val score: Double)

class RerankResult(val ranking: List<RankedDocument>, val warnings: List<AiWarning> = emptyList())
