package neton.ai

/**
 * 与厂商无关的结束原因；上游原始字符串见 [AiStep.rawFinishReason]。
 * 只有 [Stop] 与 [ToolCalls] 时才会执行工具（截断、过滤等情况下的工具调用不可信）。
 */
enum class AiFinishReason { Stop, Length, ToolCalls, ContentFilter, Error, Other }
