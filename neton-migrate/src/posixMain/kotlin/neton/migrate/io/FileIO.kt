@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package neton.migrate.io

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.DIR
import platform.posix.O_RDONLY
import platform.posix.S_IFDIR
import platform.posix.S_IFMT
import platform.posix.close
import platform.posix.closedir
import platform.posix.opendir
import platform.posix.read as posixRead
import platform.posix.readdir
import platform.posix.stat
import platform.posix.open as posixOpen

actual object FileIO {

    actual fun exists(path: String): Boolean = memScoped {
        val st = alloc<stat>()
        stat(path, st.ptr) == 0
    }

    actual fun isDirectory(path: String): Boolean = memScoped {
        val st = alloc<stat>()
        if (stat(path, st.ptr) != 0) return false
        (st.st_mode.toInt() and S_IFMT) == S_IFDIR
    }

    actual fun listDir(path: String): List<String> {
        val dir: kotlinx.cinterop.CPointer<DIR> = opendir(path) ?: return emptyList()
        val out = mutableListOf<String>()
        try {
            while (true) {
                val ent = readdir(dir) ?: break
                val name = ent.pointed.d_name.toKString()
                if (name == "." || name == "..") continue
                out += name
            }
        } finally {
            closedir(dir)
        }
        return out
    }

    actual fun readText(path: String): String? {
        @Suppress("EXPERIMENTAL_UNSIGNED_LITERALS")
        val fd = posixOpen(path, O_RDONLY)
        if (fd < 0) return null
        return try {
            val chunks = mutableListOf<ByteArray>()
            val buf = ByteArray(8192)
            var total = 0
            while (true) {
                val n = buf.usePinned { pinned ->
                    posixRead(fd, pinned.addressOf(0), 8192u)
                }
                if (n <= 0) break
                chunks += buf.copyOf(n.toInt())
                total += n.toInt()
            }
            ByteArray(total).also { out ->
                var off = 0
                for (chunk in chunks) {
                    chunk.copyInto(out, off)
                    off += chunk.size
                }
            }.decodeToString()
        } finally {
            close(fd)
        }
    }
}

actual fun currentTimeMillis(): Long {
    val nowSec = platform.posix.time(null)
    return nowSec.toLong() * 1000L
}
