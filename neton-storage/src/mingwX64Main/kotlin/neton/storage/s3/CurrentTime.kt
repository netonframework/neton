@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package neton.storage.s3

import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.posix.mingw_gettimeofday
import platform.posix.timeval

internal actual fun currentTimeMillis(): Long {
    return memScoped {
        val tv = alloc<timeval>()
        mingw_gettimeofday(tv.ptr, null)
        tv.tv_sec * 1000L + tv.tv_usec / 1000L
    }
}
