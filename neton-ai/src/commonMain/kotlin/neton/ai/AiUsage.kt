package neton.ai

/**
 * Token usage. ALL fields nullable: providers vary (some give only total, some only input/output,
 * streaming sometimes omits usage). AiClient does NOT auto-derive totalTokens from input+output;
 * downstream aggregation is the recorder's responsibility.
 */
data class AiUsage(
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val totalTokens: Int? = null,
)
