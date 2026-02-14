package neton.http

/**
 * 安装端口占用时的全局异常钩子，避免打印几十行堆栈。
 * Native 下 EADDRINUSE 在 Ktor 内部协程抛出，try-catch 无法捕获，需用 setUnhandledExceptionHook。
 */
internal expect fun installPortInUseHandler(port: Int)
