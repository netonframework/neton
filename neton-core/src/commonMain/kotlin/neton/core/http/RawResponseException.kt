package neton.core.http

/**
 * 原样响应异常 —— 路由层捕获后**不套响应信封**，按 [status] / [contentType] / [body] 直接写回。
 *
 * 只给对外协议有自己错误格式的场景用（如 OpenAI / Anthropic / Gemini 兼容网关：官方 SDK 只认
 * `{"error":{...}}`，读不懂 `{code,message}` 信封）。典型来源是
 * [neton.core.interfaces.Authenticator.unauthenticatedResponse]。普通业务错误仍然 throw [HttpException]。
 */
class RawResponseException(
    val status: Int,
    val body: String,
    val contentType: String = "application/json; charset=utf-8",
    val headers: Map<String, String> = emptyMap(),
) : RuntimeException("raw response $status")
