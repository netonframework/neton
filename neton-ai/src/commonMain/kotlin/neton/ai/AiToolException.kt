package neton.ai

/**
 * 工具执行失败时，**主动**要告诉模型的信息。
 *
 * 工具抛出的其他任何异常，工具循环只把固定的 [AiToolFailure.GENERIC_MESSAGE] 发给模型，异常详情只写日志：
 * 数据库错误、内部地址、凭据片段都可能出现在异常消息里，不能原样发往外部 provider（spec/ai.md §8）。
 * 需要让模型据此调整（例如"城市名无效，请换一个"）时，抛出本异常并写好面向模型的 [publicMessage]。
 */
@neton.ai.ExperimentalNetonAiApi
class AiToolException(
    val publicMessage: String,
    /** 稳定的错误码，便于模型与日志识别。 */
    val code: String = "tool_error",
    cause: Throwable? = null,
) : RuntimeException(publicMessage, cause)

/** 工具失败时发给模型的内容。 */
@neton.ai.ExperimentalNetonAiApi
object AiToolFailure {
    const val GENERIC_CODE = "tool_execution_failed"
    const val GENERIC_MESSAGE = "The tool failed to execute."

    /** 把异常转成可以安全发给模型的工具结果内容（JSON），并把详情交给 [logSink]。 */
    internal fun contentFor(toolName: String, e: Throwable, logSink: AiLogSink?): String {
        val (code, message) = when (e) {
            is AiToolException -> e.code to e.publicMessage
            else -> GENERIC_CODE to GENERIC_MESSAGE
        }
        logSink?.invoke("neton-ai tool failed: tool=$toolName code=$code error=${e::class.simpleName}: ${e.message ?: ""}")
        return kotlinx.serialization.json.buildJsonObject {
            put("error", kotlinx.serialization.json.JsonPrimitive(code))
            put("message", kotlinx.serialization.json.JsonPrimitive(message))
        }.toString()
    }
}
