package neton.ai.testing

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import neton.ai.AiClient
import neton.ai.AiContent
import neton.ai.AiError
import neton.ai.AiException
import neton.ai.AiFinishReason
import neton.ai.AiMessage
import neton.ai.AiModelId
import neton.ai.AiRole
import neton.ai.AiStreamEvent
import neton.ai.AiToolCall
import neton.ai.AiUsage
import neton.ai.provider.AiEmbeddingModel
import neton.ai.provider.AiProvider
import neton.ai.provider.AiStreamingTextModel
import neton.ai.provider.AiTextModel
import neton.ai.provider.ProviderCallRequest
import neton.ai.provider.ProviderCallResponse
import neton.ai.routing.RoutingConfig
import neton.ai.usage.NoopAiUsageRecorder

/**
 * 一轮模型输出的脚本（spec/ai.md §10.4：测试替身）。
 * 非流式直接返回；流式按 [chunkSize] 把文本切成增量，每个事件之间等待 [delayMillis]，用来验证取消与时序。
 */
sealed interface AiScript {
    data class Reply(
        val content: List<AiContent>,
        val finishReason: AiFinishReason? = null,
        val usage: AiUsage? = AiUsage(inputTokens = 1, outputTokens = 1),
        val chunkSize: Int = 4,
        val delayMillis: Long = 0,
    ) : AiScript

    data class Fail(val error: AiError, val afterTextDeltas: String = "") : AiScript

    companion object {
        fun text(text: String, delayMillis: Long = 0) = Reply(listOf(AiContent.Text(text)), delayMillis = delayMillis)
        fun toolCalls(vararg calls: AiToolCall, text: String? = null) =
            Reply(listOfNotNull(text?.let { AiContent.Text(it) }) + calls.map { AiContent.ToolCall(it) })
        fun reasoning(reasoning: String, text: String) = Reply(listOf(AiContent.Reasoning(reasoning), AiContent.Text(text)))
        fun refusal(text: String) = Reply(listOf(AiContent.Refusal(text)))
        fun truncated(text: String) = Reply(listOf(AiContent.Text(text)), finishReason = AiFinishReason.Length)
        fun error(error: AiError) = Fail(error)
    }
}

/**
 * 按顺序回放脚本的 provider：每次模型调用消费一条脚本（用完后重复最后一条）。
 * [requests] 记录收到的全部请求，便于断言「发了什么」。
 */
class ScriptedAiProvider(
    override val id: String = "scripted",
    private val scripts: List<AiScript>,
) : AiProvider {
    private val _requests = mutableListOf<ProviderCallRequest>()
    val requests: List<ProviderCallRequest> get() = _requests.toList()
    private var cursor = 0

    private fun next(request: ProviderCallRequest): AiScript {
        _requests += request
        return scripts[cursor.coerceAtMost(scripts.lastIndex)].also { cursor++ }
    }

    private val model = object : AiStreamingTextModel {
        override val providerId: String get() = id
        override val modelName: String = "scripted"

        override suspend fun generate(request: ProviderCallRequest): ProviderCallResponse = when (val s = next(request)) {
            is AiScript.Fail -> throw AiException(s.error)
            is AiScript.Reply -> response(s)
        }

        override fun stream(request: ProviderCallRequest): Flow<AiStreamEvent> = flow {
            when (val s = next(request)) {
                is AiScript.Fail -> {
                    s.afterTextDeltas.chunked(4).forEach { emit(AiStreamEvent.TextDelta(it)) }
                    emit(AiStreamEvent.Failed(s.error))
                }
                is AiScript.Reply -> {
                    for (c in s.content) {
                        when (c) {
                            is AiContent.Text -> c.text.chunked(s.chunkSize.coerceAtLeast(1)).forEach { pause(s); emit(AiStreamEvent.TextDelta(it)) }
                            is AiContent.Refusal -> { pause(s); emit(AiStreamEvent.TextDelta(c.text)) }
                            is AiContent.Reasoning -> { pause(s); emit(AiStreamEvent.ReasoningDelta(c.text)) }
                            is AiContent.ToolCall -> {
                                pause(s)
                                emit(AiStreamEvent.ToolCallStarted(c.call.id, c.call.name))
                                emit(AiStreamEvent.ToolCallArgumentsDelta(c.call.id, c.call.argumentsJson))
                                emit(AiStreamEvent.ToolCallReady(c.call))
                            }
                            else -> {}
                        }
                    }
                    val r = response(s)
                    emit(AiStreamEvent.Completed(r.message, r.text, r.usage, r.finishReason))
                }
            }
        }
    }

    private suspend fun pause(s: AiScript.Reply) { if (s.delayMillis > 0) delay(s.delayMillis) }

    private fun response(s: AiScript.Reply): ProviderCallResponse {
        val message = AiMessage(AiRole.Assistant, s.content)
        val finish = s.finishReason ?: if (message.toolCalls.isNotEmpty()) AiFinishReason.ToolCalls else AiFinishReason.Stop
        return ProviderCallResponse(message, message.text, message.toolCalls, s.usage, finish)
    }

    override fun textModel(modelName: String): AiTextModel = model
    override fun streamingTextModel(modelName: String): AiStreamingTextModel = model
    override fun embeddingModel(modelName: String): AiEmbeddingModel? = null

    /** 用这个 provider 构造一个 [AiClient]（默认模型即本 provider），不需要 HTTP。 */
    fun client(): AiClient = neton.ai.internal.AiClientFactory.create(
        registry = neton.ai.internal.DefaultProviderRegistry(mapOf(id to this)),
        router = neton.ai.internal.DefaultModelRouter(RoutingConfig(defaultModel = AiModelId(id, "scripted"))),
        recorder = NoopAiUsageRecorder,
    )
}
