package neton.ai

/**
 * Provider-neutral message. Spec §3.2 option α (separate fields, not content blocks).
 *
 * - role=Assistant with toolCalls → model requesting tools
 * - role=Tool with toolCallId    → tool execution result going back to model
 * - role=Tool with toolResultIsError=true → tool execution failed and should be surfaced to providers that support it
 * - metadata: lightweight provider-specific hints (e.g., providerMessageId); do NOT put secrets here
 */
data class AiMessage(
    val role: AiRole,
    val content: List<AiContent> = emptyList(),
    val toolCalls: List<AiToolCall> = emptyList(),
    val toolCallId: String? = null,
    val metadata: Map<String, String> = emptyMap(),
    val toolResultIsError: Boolean? = null,
)
