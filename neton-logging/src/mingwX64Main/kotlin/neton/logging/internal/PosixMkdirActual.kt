@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package neton.logging.internal

import platform.posix.mkdir

/**
 * MinGW：mkdir 无 mode 参数，仅传 path。
 */
internal actual fun createDirWithMode(path: String) {
    mkdir(path)
}
