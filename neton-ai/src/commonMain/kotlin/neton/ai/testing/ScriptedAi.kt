package neton.ai.testing

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import neton.ai.AiClient
import neton.ai.AiError
import neton.ai.AiException
import neton.ai.AiModelId
import neton.ai.model.AiLanguageModel
import neton.ai.model.AiModelCallOptions
import neton.ai.model.AiModelResult
import neton.ai.model.AiModelStream
import neton.ai.model.AiWarning
import neton.ai.protocol.ir.*
import neton.ai.protocol.usage.Usage
import neton.ai.provider.AiProvider
import neton.ai.routing.RoutingConfig
import neton.ai.usage.NoopAiUsageRecorder

/**
 * 一轮模型输出的脚本（spec/ai.md §10.4：测试替身）。内容直接用协议层 IR 块描述。
 * 流式时文本按 [chunkSize] 切成增量，事件之间等待 [delayMillis]，用来验证取消与时序。
 */
sealed interface AiScript {
    data class Reply(
        val content: List<Block>,
        val finishReason: FinishReason? = null,
        val usage: Usage? = Usage(inputTokens = 1, outputTokens = 1),
        val warnings: List<AiWarning> = emptyList(),
        val chunkSize: Int = 4,
        val delayMillis: Long = 0,
    ) : AiScript

    /** [afterText] 非空时流式会先发出这些文本再失败（验证中途失败）。 */
    data class Fail(val error: AiError, val afterText: String = "") : AiScript

    companion object {
        fun text(text: String, delayMillis: Long = 0) = Reply(listOf(TextBlock(text)), delayMillis = delayMillis)
        fun toolCall(id: String, name: String, arguments: String = "{}", text: String? = null) =
            Reply(listOfNotNull(text?.let { TextBlock(it) }, ToolCallBlock(id, name, arguments)))
        fun toolCalls(vararg calls: ToolCallBlock) = Reply(calls.toList())
        fun reasoning(reasoning: String, text: String, signature: String? = null) = Reply(listOf(ReasoningBlock(reasoning, signature), TextBlock(text)))
        fun refusal(text: String) = Reply(listOf(RefusalBlock(text)))
        fun truncated(text: String) = Reply(listOf(TextBlock(text)), finishReason = FinishReason.Length)
        fun error(error: AiError) = Fail(error)
    }
}

/**
 * 按顺序回放脚本的 provider：每次模型调用消费一条脚本（用完后重复最后一条）。
 * [requests] 记录收到的全部请求（IR），便于断言「发了什么」。
 */
class ScriptedAiProvider(
    override val id: String = "scripted",
    private val scripts: List<AiScript>,
) : AiProvider {
    private val _requests = mutableListOf<ChatRequest>()
    val requests: List<ChatRequest> get() = _requests.toList()
    private var cursor = 0

    private fun next(request: ChatRequest): AiScript {
        _requests += request
        return scripts[cursor.coerceAtMost(scripts.lastIndex)].also { cursor++ }
    }

    private fun response(s: AiScript.Reply) = ChatResponse(
        content = s.content,
        finishReason = s.finishReason ?: if (s.content.any { it is ToolCallBlock }) FinishReason.ToolCalls else FinishReason.Stop,
        usage = s.usage,
    )

    override fun languageModel(modelId: String): AiLanguageModel = object : AiLanguageModel {
        override val provider: String get() = id
        override val modelId: String = modelId

        override suspend fun generate(request: ChatRequest, options: AiModelCallOptions): AiModelResult = when (val s = next(request)) {
            is AiScript.Fail -> throw AiException(s.error)
            is AiScript.Reply -> AiModelResult(response(s), s.warnings)
        }

        override suspend fun stream(request: ChatRequest, options: AiModelCallOptions): AiModelStream {
            val s = next(request)
            val events = flow {
                when (s) {
                    is AiScript.Fail -> {
                        if (s.afterText.isNotEmpty()) {
                            emit(StreamEvent.BlockStart(0, TextBlock("")))
                            emit(StreamEvent.BlockDelta(0, Delta.Text(s.afterText)))
                        }
                        throw AiException(s.error)
                    }
                    is AiScript.Reply -> {
                        emit(StreamEvent.MessageStart())
                        s.content.forEachIndexed { i, b ->
                            when (b) {
                                is TextBlock -> {
                                    emit(StreamEvent.BlockStart(i, TextBlock("")))
                                    b.text.chunked(s.chunkSize.coerceAtLeast(1)).forEach {
                                        if (s.delayMillis > 0) delay(s.delayMillis)
                                        emit(StreamEvent.BlockDelta(i, Delta.Text(it)))
                                    }
                                }
                                is ReasoningBlock -> {
                                    emit(StreamEvent.BlockStart(i, ReasoningBlock()))
                                    if (b.text.isNotEmpty()) emit(StreamEvent.BlockDelta(i, Delta.Reasoning(b.text)))
                                    b.signature?.let { emit(StreamEvent.BlockDelta(i, Delta.Signature(it))) }
                                }
                                is ToolCallBlock -> {
                                    emit(StreamEvent.BlockStart(i, b.copy(arguments = "")))
                                    if (s.delayMillis > 0) delay(s.delayMillis)
                                    emit(StreamEvent.BlockDelta(i, Delta.Arguments(b.arguments)))
                                }
                                is RefusalBlock -> {
                                    emit(StreamEvent.BlockStart(i, RefusalBlock("")))
                                    emit(StreamEvent.BlockDelta(i, Delta.Text(b.text)))
                                }
                                else -> emit(StreamEvent.BlockStart(i, b))
                            }
                            emit(StreamEvent.BlockStop(i))
                        }
                        s.usage?.let { emit(StreamEvent.UsageUpdate(it)) }
                        val r = response(s)
                        emit(StreamEvent.Finish(r.finishReason!!))
                    }
                }
            }
            return AiModelStream((s as? AiScript.Reply)?.warnings ?: emptyList(), events)
        }
    }

    /** 用这个 provider 构造一个 [AiClient]（默认模型 `<id>:scripted`），不需要 HTTP。 */
    fun client(): AiClient = neton.ai.internal.AiClientFactory.create(
        registry = neton.ai.internal.DefaultProviderRegistry(mapOf(id to this)),
        router = neton.ai.internal.DefaultModelRouter(RoutingConfig(defaultModel = AiModelId(id, "scripted"))),
        recorder = NoopAiUsageRecorder,
    )
}
