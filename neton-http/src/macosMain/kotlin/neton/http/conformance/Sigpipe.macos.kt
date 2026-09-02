package neton.http.conformance

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.value
import platform.posix.SOL_SOCKET
import platform.posix.SO_NOSIGPIPE
import platform.posix.setsockopt

@OptIn(ExperimentalForeignApi::class)
internal actual fun disableSigpipe(fd: Int) {
    memScoped {
        val one = alloc<IntVar>().apply { value = 1 }
        setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, one.ptr, sizeOf<IntVar>().convert())
    }
}

internal actual fun sendFlags(): Int = 0
