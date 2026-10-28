package neton.ai.protocol.ir

import kotlinx.serialization.json.JsonObject
import neton.ai.protocol.error.ProtocolError
import neton.ai.protocol.usage.Usage

/**
 * 流式事件 IR（spec §6）。块按 [BlockStart]/[BlockDelta]/[BlockStop] 开闭，`index` 是本条消息内
 * 从 0 递增的块序号；同一时刻最多一个块处于打开状态（先关后开），工具调用并行时依次开闭。
 */
@neton.ai.ExperimentalNetonAiApi
sealed interface StreamEvent {
    data class MessageStart(
        val id: String? = null,
        val model: String? = null,
        val created: Long? = null,
        val metadata: ProviderMetadata = ProviderMetadata.EMPTY,
    ) : StreamEvent

    /** [block] 是块的初始形态：文本为空串、工具调用参数为空串，后续由 [BlockDelta] 补全。 */
    data class BlockStart(val index: Int, val block: Block) : StreamEvent

    data class BlockDelta(val index: Int, val delta: Delta) : StreamEvent

    /** [metadata] 携带收尾时才到的信息（如 Gemini 签名、Responses item 完成时的加密推理）。 */
    data class BlockStop(val index: Int, val metadata: ProviderMetadata = ProviderMetadata.EMPTY) : StreamEvent

    /** 用量更新；按 [Usage.mergedWith] 规则累加。 */
    data class UsageUpdate(val usage: Usage) : StreamEvent

    data class Finish(val reason: FinishReason, val raw: String? = null) : StreamEvent

    /** 上游中途出错。 */
    data class Error(val error: ProtocolError) : StreamEvent
}

@neton.ai.ExperimentalNetonAiApi
sealed interface Delta {
    data class Text(val text: String) : Delta
    data class Reasoning(val text: String) : Delta
    data class ReasoningSummary(val text: String) : Delta
    /** Anthropic thinking 签名。 */
    data class Signature(val signature: String) : Delta
    data class Arguments(val json: String) : Delta
    data class Citation(val citation: JsonObject) : Delta
}
