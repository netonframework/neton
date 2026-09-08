@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package neton.http.static

import kotlinx.cinterop.*
import platform.posix.*

internal actual object StaticFileSystem {
    actual fun stat(path: String): FileStat? = memScoped {
        val st = alloc<stat>()
        if (stat(path, st.ptr) != 0) return null
        FileStat(
            size = st.st_size,
            mtimeMillis = st.st_mtim.tv_sec * 1000L + st.st_mtim.tv_nsec / 1_000_000L,
            isDirectory = (st.st_mode.toInt() and S_IFMT) == S_IFDIR,
        )
    }
    actual fun readAll(path: String) = posixReadAll(path)
    actual fun readRange(path: String, offset: Long, length: Long) = posixReadRange(path, offset, length)
    actual fun realPath(path: String) = posixRealPath(path)
}
