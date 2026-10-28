package neton.ai.provider

import neton.ai.model.AiLanguageModel

/**
 * Provider：按模型名给出各类模型。不支持的类型返回 null（调用时报 ModelNotFound）。
 */
@neton.ai.ExperimentalNetonAiApi
interface AiProvider {
    val id: String
    fun languageModel(modelId: String): AiLanguageModel?
    fun embeddingModel(modelId: String): AiEmbeddingModel? = null
    fun imageModel(modelId: String): AiImageModel? = null
    fun speechModel(modelId: String): AiSpeechModel? = null
    fun transcriptionModel(modelId: String): AiTranscriptionModel? = null
    fun rerankingModel(modelId: String): AiRerankingModel? = null
}
