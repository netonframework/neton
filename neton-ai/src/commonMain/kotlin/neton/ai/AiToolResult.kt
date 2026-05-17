package neton.ai

/**
 * Result of executing a tool. `content` is freeform (JSON or plain text); `format` tells consumers
 * how to interpret it. `isError = true` indicates the executor threw; `content` holds the message.
 */
data class AiToolResult(
    val toolCallId: String,
    val content: String,
    val isError: Boolean = false,
    val format: AiToolResultFormat = AiToolResultFormat.Json,
)

enum class AiToolResultFormat { Json, Text }
