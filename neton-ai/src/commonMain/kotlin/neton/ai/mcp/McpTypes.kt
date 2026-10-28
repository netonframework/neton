package neton.ai.mcp

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@neton.ai.ExperimentalNetonAiApi
data class McpTool(
    val name: String,
    val description: String?,
    val inputSchema: JsonObject,
    val title: String? = null,
    /** 行为提示（readOnlyHint、destructiveHint、idempotentHint、openWorldHint），只是提示，不能当作安全保证。 */
    val annotations: JsonObject? = null,
)

@neton.ai.ExperimentalNetonAiApi
data class McpResource(val uri: String, val name: String?, val description: String?, val mimeType: String?)

@neton.ai.ExperimentalNetonAiApi
data class McpPrompt(val name: String, val description: String?, val arguments: List<String>)

@neton.ai.ExperimentalNetonAiApi
data class McpToolResult(
    /** 文本内容拼接（非文本内容以类型占位）。 */
    val text: String,
    val isError: Boolean,
    val structuredContent: JsonElement? = null,
    val raw: JsonObject,
)

@neton.ai.ExperimentalNetonAiApi
data class McpServerInfo(val name: String, val version: String)

/** 服务端发来的通知（进度、日志、列表变更等）。 */
@neton.ai.ExperimentalNetonAiApi
data class McpNotification(val method: String, val params: JsonObject)

/** 协议世代：2026-07-28 起是无状态的 modern；之前是 initialize 握手 + 会话的 legacy。 */
@neton.ai.ExperimentalNetonAiApi
enum class McpProtocolEra { Modern, Legacy }

/** MCP 错误分类。工具自己报告的失败（`isError`）不是异常，见 [McpToolResult.isError]。 */
@neton.ai.ExperimentalNetonAiApi
enum class McpErrorKind {
    /** 连接失败、断流、响应被截断。 */
    Transport,
    /** 请求超时。 */
    Timeout,
    /** 服务端回了非 2xx，且正文不是 JSON-RPC 错误（见 [McpException.httpStatus]）。 */
    Http,
    /** 服务端回了 JSON-RPC 错误（见 [McpException.code]、[McpException.data]）。 */
    Rpc,
    /** 服务端的响应不符合协议（格式错误、缺字段、超过大小限制）。 */
    Protocol,
    /** 双方能力不匹配：服务端没声明该能力、协议版本不受支持、要求多轮输入等。 */
    Unsupported,
    /** 调用方给的参数不合法（如与 `x-mcp-header` 标注的类型不符），请求没有发出。 */
    InvalidArgument,
    /** legacy 会话已被服务端结束；客户端会自动重新握手并重发一次。 */
    SessionExpired,
    /** 客户端已关闭。 */
    Closed,
}

/**
 * MCP 调用失败。[outcomeUnknown] 为 true 表示请求可能已被服务端执行（断流、超时）：
 * 对有副作用的工具调用不要盲目重试。协程取消不会被包装成这个异常。
 */
@neton.ai.ExperimentalNetonAiApi
class McpException(
    val kind: McpErrorKind,
    message: String,
    /** JSON-RPC 错误码。 */
    val code: Int? = null,
    val httpStatus: Int? = null,
    /** JSON-RPC 错误的附加数据（如服务端支持的协议版本）。 */
    val data: JsonElement? = null,
    val outcomeUnknown: Boolean = false,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
