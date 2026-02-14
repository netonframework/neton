package neton.logging

import kotlin.native.concurrent.ThreadLocal

@ThreadLocal
private var currentLogContextStorage: LogContext? = null

actual object CurrentLogContext {
    actual fun get(): LogContext? = currentLogContextStorage
    actual fun set(ctx: LogContext?) {
        currentLogContextStorage = ctx
    }
    actual fun clear() {
        currentLogContextStorage = null
    }
}
