package neton.ai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll

/**
 * 可复用的 Agent：把模型、指令、工具、停止条件等设置固定下来，每次只传对话（spec/ai.md §10.3）。
 *
 * Agent 无状态：历史由调用方保存，每次把完整消息传进来，再把 [AiResult.responseMessages] 接到历史后面。
 * 默认最多 20 步。
 */
@neton.ai.ExperimentalNetonAiApi
class AiAgent(
    private val client: AiClient,
    /** 模板：除 messages 外的所有设置。 */
    val settings: AiRequest,
    /** 每次调用前改写请求（按调用参数切换模型、工具、指令等）。 */
    private val prepareCall: (suspend (AiRequest) -> AiRequest)? = null,
) {
    suspend fun generate(prompt: String): AiResult = generate(listOf(AiMessage.user(prompt)))
    suspend fun generate(messages: List<AiMessage>): AiResult = client.generate(prepare(messages))

    fun stream(prompt: String): Flow<AiStreamPart> = stream(listOf(AiMessage.user(prompt)))
    fun stream(messages: List<AiMessage>): Flow<AiStreamPart> = kotlinx.coroutines.flow.flow { emitAll(client.stream(prepare(messages))) }

    private suspend fun prepare(messages: List<AiMessage>): AiRequest {
        val r = settings.copy(messages = messages)
        return prepareCall?.invoke(r) ?: r
    }
}

/** `client.agent { model = "openai:gpt-5"; instructions = "..."; tools(...) }` */
@neton.ai.ExperimentalNetonAiApi
fun AiClient.agent(prepareCall: (suspend (AiRequest) -> AiRequest)? = null, block: AiRequestBuilder.() -> Unit): AiAgent =
    AiAgent(this, aiRequest(block), prepareCall)
