@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)

package neton.core.config

import kotlin.concurrent.AtomicInt
import kotlin.native.setUnhandledExceptionHook
import kotlin.native.terminateWithUnhandledException

private val installed = AtomicInt(0)

/**
 * 未被捕获的 [ConfigOverrideException] 以退出码 1 结束进程（5.4.2），而不是 Kotlin/Native
 * 默认的 abort（134）。脚本按退出码 1 判定"配置错误、启动失败"，和端口占用的处理方式一致。
 *
 * 只接管这一种异常（含被包装在 cause 链里的情况，组件常把配置错误包一层再抛）；
 * 其余异常交回原来的钩子，没有原钩子就按默认方式终止。
 */
internal actual fun exitOnUncaughtConfigOverrideError() {
    if (!installed.compareAndSet(0, 1)) return
    var previous: ((Throwable) -> Unit)? = null
    previous = setUnhandledExceptionHook { e ->
        var t: Throwable? = e
        while (t != null) {
            if (t is ConfigOverrideException) {
                println("STARTUP ABORTED: ${t.message}")
                kotlin.system.exitProcess(1)
            }
            t = t.cause
        }
        previous?.invoke(e) ?: terminateWithUnhandledException(e)
    }
}
