@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package neton.logging.internal

import platform.posix.mkdir

internal actual fun createDirWithMode(path: String) {
    @Suppress("EXPERIMENTAL_UNSIGNED_LITERALS")
    mkdir(path, 509u) // 0755
}
