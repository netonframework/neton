package neton.http.conformance

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.sizeOf
import platform.posix.AF_INET
import platform.posix.SOL_SOCKET
import platform.posix.SO_RCVTIMEO
import platform.posix.setsockopt
import platform.posix.sockaddr_in
import platform.posix.timeval

@OptIn(ExperimentalForeignApi::class)
internal actual fun sockaddr_in.setInetFamily() {
    sin_family = AF_INET.convert()
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun setReceiveTimeout(fd: Int, seconds: Int) {
    memScoped {
        val timeout = alloc<timeval>().apply {
            tv_sec = seconds.convert()
            tv_usec = 0.convert()
        }
        setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, timeout.ptr, sizeOf<timeval>().convert())
    }
}
