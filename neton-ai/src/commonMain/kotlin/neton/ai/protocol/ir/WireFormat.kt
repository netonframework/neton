package neton.ai.protocol.ir

/** 四种文本协议（neton-docs spec/ai.md §3.1）。[id] 是稳定字符串，可用于配置与日志。 */
enum class WireFormat(val id: String) {
    OPENAI_CHAT("openai_chat"),
    OPENAI_RESPONSES("openai_responses"),
    ANTHROPIC("anthropic"),
    GEMINI("gemini");

    companion object {
        fun fromId(id: String): WireFormat? = entries.firstOrNull { it.id == id }
    }
}
