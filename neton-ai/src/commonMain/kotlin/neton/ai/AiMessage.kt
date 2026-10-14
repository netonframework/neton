package neton.ai

/**
 * 与厂商无关的消息。
 * - Assistant：按顺序放文本、推理、[AiContent.ToolCall] 与 [AiContent.ToolApprovalRequest]；
 * - Tool：放 [AiContent.ToolResult] 与 [AiContent.ToolApprovalResponse]（一条消息可以有多个）。
 */
data class AiMessage(
    val role: AiRole,
    val content: List<AiContent> = emptyList(),
    /** 轻量的提示信息，**不要**放密钥。 */
    val metadata: Map<String, String> = emptyMap(),
) {
    val toolCalls: List<AiToolCall> get() = content.filterIsInstance<AiContent.ToolCall>().map { it.call }
    val toolResults: List<AiContent.ToolResult> get() = content.filterIsInstance<AiContent.ToolResult>()

    /** 全部文本块拼接（不含推理与拒绝）。 */
    val text: String get() = content.filterIsInstance<AiContent.Text>().joinToString("") { it.text }

    val reasoningText: String get() = content.filterIsInstance<AiContent.Reasoning>().joinToString("") { it.text }

    companion object {
        fun system(text: String) = AiMessage(AiRole.System, listOf(AiContent.Text(text)))
        fun user(text: String) = AiMessage(AiRole.User, listOf(AiContent.Text(text)))
        fun user(vararg content: AiContent) = AiMessage(AiRole.User, content.toList())

        /** 文本在前、工具调用在后的 assistant 消息（手工构造历史时用；模型返回的消息自带原始顺序）。 */
        fun assistant(text: String? = null, toolCalls: List<AiToolCall> = emptyList()) = AiMessage(
            AiRole.Assistant,
            listOfNotNull(text?.let { AiContent.Text(it) }) + toolCalls.map { AiContent.ToolCall(it) },
        )

        fun tool(vararg content: AiContent) = AiMessage(AiRole.Tool, content.toList())

        fun toolResult(toolCallId: String, toolName: String, output: AiToolOutput) =
            AiMessage(AiRole.Tool, listOf(AiContent.ToolResult(toolCallId, toolName, output)))

        /** 对挂起的审批作答，作为最后一条消息发回即可继续（见 [AiResult.pendingApprovals]）。 */
        fun approvals(vararg responses: AiContent.ToolApprovalResponse) = AiMessage(AiRole.Tool, responses.toList())
    }
}
