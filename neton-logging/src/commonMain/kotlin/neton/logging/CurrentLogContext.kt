package neton.logging

/**
 * Legacy thread-local context for synchronous code only.
 * Never keep this set across a suspension point. Request coroutines use
 * [LogContext] in CoroutineContext and [withCurrentContext] instead.
 */
expect object CurrentLogContext {
    fun get(): LogContext?
    fun set(ctx: LogContext?)
    fun clear()
}
