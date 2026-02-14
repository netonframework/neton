package neton.logging

/**
 * 请求/链路上下文（v1 冻结）。
 * 存在于 CoroutineContext / NetonContext；Logger 实现自动注入这些字段，业务不手传 traceId。
 */
data class LogContext(
    val traceId: String,
    val spanId: String? = null,
    val requestId: String? = null,
    val userId: String? = null
)
