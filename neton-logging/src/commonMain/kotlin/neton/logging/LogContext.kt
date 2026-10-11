package neton.logging

/**
 * 请求/链路上下文（v1 冻结）。
 * Stored in CoroutineContext. Capture it with Logger.withCurrentContext() once
 * per request; the resulting logger is safe across suspension and thread changes.
 */
data class LogContext(
    val traceId: String,
    val spanId: String? = null,
    val requestId: String? = null,
    val userId: String? = null
) : kotlin.coroutines.AbstractCoroutineContextElement(Key) {
    companion object Key : kotlin.coroutines.CoroutineContext.Key<LogContext>
}

/** Bind an immutable request context without relying on thread-local state. */
fun Logger.withContext(context: LogContext): Logger =
    BoundContextLogger((this as? BoundContextLogger)?.delegate ?: this, context)

private class BoundContextLogger(val delegate: Logger, context: LogContext) : Logger {
    private val boundFields: Fields = buildMap {
        put("traceId", context.traceId)
        context.spanId?.let { put("spanId", it) }
        context.requestId?.let { put("requestId", it) }
        context.userId?.let { put("userId", it) }
    }
    private fun merge(fields: Fields): Fields = fields + boundFields
    override fun isEnabled(level: LogLevel) = delegate.isEnabled(level)
    override fun trace(msg: String, fields: Fields) = delegate.trace(msg, merge(fields))
    override fun debug(msg: String, fields: Fields) = delegate.debug(msg, merge(fields))
    override fun info(msg: String, fields: Fields) = delegate.info(msg, merge(fields))
    override fun warn(msg: String, fields: Fields, cause: Throwable?) = delegate.warn(msg, merge(fields), cause)
    override fun error(msg: String, fields: Fields, cause: Throwable?) = delegate.error(msg, merge(fields), cause)
}

/** Call from a request coroutine, not once when constructing a shared service. */
suspend fun Logger.withCurrentContext(): Logger =
    kotlinx.coroutines.currentCoroutineContext()[LogContext]?.let { withContext(it) } ?: this
