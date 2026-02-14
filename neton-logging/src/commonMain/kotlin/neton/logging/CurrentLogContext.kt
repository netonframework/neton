package neton.logging

/**
 * 请求级「当前 LogContext」持有者（expect/actual）。
 * 请求入口 set(logContext)，handler 内 Logger 通过 contextProvider 读 get()，finally clear()。
 * 避免在非 suspend 的 log() 中调用 currentCoroutineContext()，且 KMP/Native 友好。
 */
expect object CurrentLogContext {
    fun get(): LogContext?
    fun set(ctx: LogContext?)
    fun clear()
}
