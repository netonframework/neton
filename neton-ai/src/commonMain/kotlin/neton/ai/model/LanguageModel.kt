package neton.ai.model

import kotlinx.coroutines.flow.Flow
import neton.ai.protocol.diag.Diagnostic
import neton.ai.protocol.diag.Severity
import neton.ai.protocol.ir.ChatRequest
import neton.ai.protocol.ir.ChatResponse
import neton.ai.protocol.ir.StreamEvent

/**
 * 语言模型接口（spec/ai.md §10）。调用参数与结果**直接就是协议层 IR**：
 * 一套类型同时服务 SDK、网关与中间件，不再有第二套「provider DTO」。
 *
 * 实现约定：
 * - 失败抛 [neton.ai.AiException]（携带分类后的 [neton.ai.AiError]）；
 * - [stream] 返回的事件流是冷流：连接与鉴权错误在收集时抛出（调用方据此在首个事件前重试或回退）。
 */
interface AiLanguageModel {
    val provider: String
    val modelId: String

    suspend fun generate(request: ChatRequest, options: AiModelCallOptions = AiModelCallOptions()): AiModelResult
    suspend fun stream(request: ChatRequest, options: AiModelCallOptions = AiModelCallOptions()): AiModelStream
}

/** 每次模型调用的传输层选项（不进入报文语义）。 */
data class AiModelCallOptions(
    /** 额外请求头，覆盖同名的默认头。 */
    val headers: Map<String, String> = emptyMap(),
    /** 透传给 HTTP 客户端的元数据（追踪等）。 */
    val metadata: Map<String, String> = emptyMap(),
    /** 本次调用的 HTTP 超时（请求 + 读空闲），覆盖 provider 的 `timeoutMillis`；null = 用 provider 的。 */
    val timeoutMillis: Long? = null,
)

data class AiModelResult(
    val response: ChatResponse,
    val warnings: List<AiWarning> = emptyList(),
    /** 上游响应头（如限流信息），没有为空。 */
    val responseHeaders: Map<String, String> = emptyMap(),
)

class AiModelStream(
    /** 编码请求时就已确定的警告（目标协议表达不了的参数等）。 */
    val warnings: List<AiWarning>,
    val events: Flow<StreamEvent>,
)

/**
 * 调用警告：请求能发出去，但有设置没有按原样生效。来自协议层诊断：
 * error 级 → [Type.UNSUPPORTED]（行为会变），warning 级 → [Type.COMPATIBILITY]（近似实现）。
 */
data class AiWarning(val type: Type, val feature: String, val message: String) {
    enum class Type { UNSUPPORTED, COMPATIBILITY, DEPRECATED, OTHER }

    companion object {
        fun of(d: Diagnostic) = AiWarning(
            if (d.severity == Severity.ERROR) Type.UNSUPPORTED else Type.COMPATIBILITY,
            "${d.code}@${d.path}", d.message,
        )
    }
}
