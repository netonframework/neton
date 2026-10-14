package neton.ai.provider

import neton.ai.AiMessage
import neton.ai.AiToolDefinition
import neton.ai.ToolChoice

data class ProviderCallRequest(
    val messages: List<AiMessage>,
    val tools: List<AiToolDefinition>,
    val toolChoice: ToolChoice,
    val temperature: Double?,
    val maxTokens: Int?,
    val topP: Double?,
    val stopSequences: List<String>,
    val metadata: Map<String, String>,
    /** 其余调用选项（推理、结构化输出、厂商选项等），与协议层共用（spec §4.3）。 */
    val options: neton.ai.protocol.ir.CallOptions = neton.ai.protocol.ir.CallOptions(),
)
