@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package neton.http

import platform.posix.getenv
import platform.posix.setenv

internal actual fun syncKtorLogLevelToConfig(level: String) {
    // 用户已显式设置 KTOR_LOG_LEVEL 时尊重，否则用框架 logging.level
    if (getenv("KTOR_LOG_LEVEL") != null) return
    setenv("KTOR_LOG_LEVEL", level, 1)
}
