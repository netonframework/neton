@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package neton.http.static

import kotlinx.cinterop.*
import platform.posix.*

/** Shared posix bodies; the `actual object` per OS calls these plus its own stat(). */
internal fun posixReadAll(path: String): ByteArray? {
    val fd = open(path, O_RDONLY)
    if (fd < 0) return null
    return try {
        val chunks = mutableListOf<ByteArray>()
        val buf = ByteArray(65536)
        var total = 0
        while (true) {
            val n = buf.usePinned { read(fd, it.addressOf(0), 65536u) }
            if (n <= 0) break
            chunks.add(buf.copyOf(n.toInt())); total += n.toInt()
        }
        ByteArray(total).also { out ->
            var off = 0
            for (c in chunks) { c.copyInto(out, off); off += c.size }
        }
    } finally { close(fd) }
}

internal fun posixReadRange(path: String, offset: Long, length: Long): ByteArray? {
    if (length <= 0) return ByteArray(0)
    val fd = open(path, O_RDONLY)
    if (fd < 0) return null
    return try {
        if (lseek(fd, offset.convert(), SEEK_SET) < 0) return null
        val out = ByteArray(length.toInt())
        var got = 0
        while (got < out.size) {
            val n = out.usePinned { read(fd, it.addressOf(got), (out.size - got).convert()) }
            if (n <= 0) break
            got += n.toInt()
        }
        if (got == out.size) out else out.copyOf(got)
    } finally { close(fd) }
}

internal fun posixRealPath(path: String): String? {
    val resolved = realpath(path, null) ?: return null
    return try { resolved.toKString() } finally { free(resolved) }
}
