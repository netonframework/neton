package neton.ai

/**
 * 与厂商无关的消息。
 *
 * - role=Assistant：[content] 里按顺序放文本、推理与 [AiContent.ToolCall]；
 * - role=Tool：[toolCallId] 指向对应的调用，[content] 是执行结果，[toolResultIsError] 标记执行失败；
 * - [metadata]：轻量的提示信息，**不要**放密钥。
 */
data class AiMessage(
    val role: AiRole,
    val content: List<AiContent> = emptyList(),
    val toolCallId: String? = null,
    val metadata: Map<String, String> = emptyMap(),
    val toolResultIsError: Boolean? = null,
) {
    /** 本条消息里的工具调用（按出现顺序）。 */
    val toolCalls: List<AiToolCall> get() = content.filterIsInstance<AiContent.ToolCall>().map { it.call }

    /** 全部文本块拼接（不含推理）。 */
    val text: String get() = content.filterIsInstance<AiContent.Text>().joinToString("") { it.text }

    /** 全部推理文本拼接。 */
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

        fun toolResult(toolCallId: String, content: String, isError: Boolean = false) = AiMessage(
            AiRole.Tool, listOf(AiContent.Text(content)), toolCallId = toolCallId, toolResultIsError = if (isError) true else null,
        )
    }
}
