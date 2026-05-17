package neton.ai

/**
 * Provider-neutral finish reason. Provider mappers must NOT leak provider-specific strings here.
 * Use `Other` as catch-all.
 *
 * Note: failure does NOT map to a finish reason; failures flow through AiError / AiException.
 */
enum class AiFinishReason {
    Stop,
    Length,
    ToolCalls,
    ContentFilter,
    Other,
}
