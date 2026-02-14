@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)
package neton.http

import kotlin.native.setUnhandledExceptionHook
import kotlin.native.terminateWithUnhandledException

private fun isPortInUse(e: Throwable): Boolean {
    var t: Throwable? = e
    while (t != null) {
        val msg = t.message ?: ""
        val name = t::class.simpleName ?: ""
        if (msg.contains("EADDRINUSE") || msg.contains("Address already in use") || name.contains("AddressAlreadyInUse")) return true
        t = t.cause
    }
    return false
}

internal actual fun installPortInUseHandler(port: Int) {
    setUnhandledExceptionHook { e ->
        if (isPortInUse(e)) {
            kotlin.io.println("Port $port is already in use. Stop the other process or use a different port.")
            kotlin.system.exitProcess(1)
        }
        terminateWithUnhandledException(e)
    }
}
