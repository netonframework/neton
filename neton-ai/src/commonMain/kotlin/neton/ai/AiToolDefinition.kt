package neton.ai

/**
 * Tool registered for a single request. `executor` is optional:
 *  - non-null: AiClient runs it locally inside the tool loop
 *  - null: AiClient returns the tool_calls to the caller (caller decides remote execution / approval / etc.)
 *
 * `inputSchemaJson` is the JSON Schema string sent to the model. v0.1 does NOT auto-generate from
 * @Serializable types; caller passes the schema string. (v0.2 may add a schema generator.)
 */
data class AiToolDefinition(
    val name: String,
    val description: String,
    val inputSchemaJson: String,
    val executor: AiToolExecutor? = null,
)

fun interface AiToolExecutor {
    /** Input is the raw argumentsJson produced by the model; return value becomes AiToolResult.content. */
    suspend fun execute(argumentsJson: String): String
}
