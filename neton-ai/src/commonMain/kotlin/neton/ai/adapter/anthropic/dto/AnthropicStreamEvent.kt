package neton.ai.adapter.anthropic.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class AnthropicMessageStartData(
    val message: AnthropicStreamMessage,
)

@Serializable
internal data class AnthropicStreamMessage(
    val id: String? = null,
    val model: String? = null,
    val usage: AnthropicUsage? = null,
)

@Serializable
internal data class AnthropicContentBlockStartData(
    val index: Int,
    @SerialName("content_block") val contentBlock: AnthropicContentBlock,
)

/** content_block_delta — delta type and payload */
@Serializable
internal data class AnthropicContentBlockDeltaData(
    val index: Int,
    val delta: AnthropicBlockDelta,
)

@Serializable
internal data class AnthropicBlockDelta(
    val type: String,                                     // "text_delta" | "input_json_delta"
    val text: String? = null,                             // for text_delta
    @SerialName("partial_json") val partialJson: String? = null,  // for input_json_delta
)

@Serializable
internal data class AnthropicContentBlockStopData(
    val index: Int,
)

@Serializable
internal data class AnthropicMessageDeltaData(
    val delta: AnthropicMessageDeltaPayload,
    val usage: AnthropicUsage? = null,    // CUMULATIVE, not incremental
)

@Serializable
internal data class AnthropicMessageDeltaPayload(
    @SerialName("stop_reason") val stopReason: String? = null,
    @SerialName("stop_sequence") val stopSequence: String? = null,
)

@Serializable
internal data class AnthropicStreamErrorData(
    val type: String = "error",
    val error: AnthropicErrorBody,
)
