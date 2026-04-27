@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package neton.migrate.io

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.O_RDONLY
import platform.posix.S_IFDIR
import platform.posix.S_IFMT
import platform.posix._close
import platform.posix._open
import platform.posix._read
import platform.posix._stat64
import platform.posix.time

actual object FileIO {

    actual fun exists(path: String): Boolean = memScoped {
        val st = alloc<_stat64>()
        platform.posix._stat64(path, st.ptr) == 0
    }

    actual fun isDirectory(path: String): Boolean = memScoped {
        val st = alloc<_stat64>()
        if (platform.posix._stat64(path, st.ptr) != 0) return false
        (st.st_mode.toInt() and S_IFMT) == S_IFDIR
    }

    actual fun listDir(path: String): List<String> {
        // Windows 目录遍历用 FindFirstFile/FindNextFile；v0.1 暂不支持 Windows 实地运行，
        // 但保证模块能在 mingwX64 平台编译通过。如果在 Windows 上需要使用，应优先使用 WSL。
        return emptyList()
    }

    actual fun readText(path: String): String? {
        val fd = _open(path, O_RDONLY)
        if (fd < 0) return null
        return try {
            val chunks = mutableListOf<ByteArray>()
            val buf = ByteArray(8192)
            var total = 0
            while (true) {
                val n = buf.usePinned { pinned ->
                    _read(fd, pinned.addressOf(0), 8192u)
                }
                if (n <= 0) break
                chunks += buf.copyOf(n)
                total += n
            }
            ByteArray(total).also { out ->
                var off = 0
                for (chunk in chunks) {
                    chunk.copyInto(out, off)
                    off += chunk.size
                }
            }.decodeToString()
        } finally {
            _close(fd)
        }
    }
}

actual fun currentTimeMillis(): Long {
    val nowSec = time(null)
    return nowSec.toLong() * 1000L
}
