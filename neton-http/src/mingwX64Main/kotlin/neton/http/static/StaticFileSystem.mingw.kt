@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package neton.http.static

import kotlinx.cinterop.*
import platform.posix.*

internal actual object StaticFileSystem {
    actual fun stat(path: String): FileStat? = memScoped {
        val st = alloc<_stat64>()
        if (_stat64(path, st.ptr) != 0) return null
        FileStat(
            size = st.st_size,
            mtimeMillis = st.st_mtime * 1000L,
            isDirectory = (st.st_mode.toInt() and S_IFMT) == S_IFDIR,
        )
    }

    actual fun readAll(path: String): ByteArray? {
        val fd = open(path, O_RDONLY or O_BINARY)
        if (fd < 0) return null
        return try {
            val chunks = mutableListOf<ByteArray>()
            val buf = ByteArray(65536)
            var total = 0
            while (true) {
                val n = buf.usePinned { read(fd, it.addressOf(0), 65536u) }
                if (n <= 0) break
                chunks.add(buf.copyOf(n)); total += n
            }
            ByteArray(total).also { out ->
                var off = 0
                for (c in chunks) { c.copyInto(out, off); off += c.size }
            }
        } finally { close(fd) }
    }

    actual fun readRange(path: String, offset: Long, length: Long): ByteArray? {
        if (length <= 0) return ByteArray(0)
        val fd = open(path, O_RDONLY or O_BINARY)
        if (fd < 0) return null
        return try {
            if (lseek(fd, offset.convert(), SEEK_SET) < 0L) return null
            val out = ByteArray(length.toInt())
            var readCount = 0
            while (readCount < out.size) {
                val n = out.usePinned { read(fd, it.addressOf(readCount), (out.size - readCount).toUInt()) }
                if (n <= 0) break
                readCount += n
            }
            if (readCount == out.size) out else out.copyOf(readCount)
        } finally { close(fd) }
    }

    actual fun realPath(path: String): String? = memScoped {
        val resolved = _fullpath(null, path, 0u) ?: return null
        try { resolved.toKString() } finally { free(resolved) }
    }
}
