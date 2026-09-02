package neton.http.conformance

import platform.posix.MSG_NOSIGNAL

internal actual fun disableSigpipe(fd: Int) = Unit

internal actual fun sendFlags(): Int = MSG_NOSIGNAL
