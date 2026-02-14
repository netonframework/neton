@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package neton.http

import platform.posix.getenv
import platform.posix._putenv

internal actual fun syncKtorLogLevelToConfig(level: String) {
    if (getenv("KTOR_LOG_LEVEL") != null) return
    _putenv("KTOR_LOG_LEVEL=$level")
}
