package neton.ai

/**
 * Model's request to invoke a tool. `argumentsJson` is the raw JSON string produced by the model;
 * caller (or DSL typed codec) parses it.
 */
data class AiToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String,
)
