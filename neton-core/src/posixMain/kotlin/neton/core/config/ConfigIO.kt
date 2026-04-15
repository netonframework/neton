@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package neton.core.config

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.get
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import neton.env.neton_get_environ
import platform.posix.O_RDONLY
import platform.posix.close
import platform.posix.open
import platform.posix.read

actual fun readConfigFile(path: String): String? {
    @Suppress("EXPERIMENTAL_UNSIGNED_LITERALS")
    val fd = open(path, O_RDONLY)
    if (fd < 0) return null
    return try {
        val chunks = mutableListOf<ByteArray>()
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val n = buf.usePinned { pinned ->
                read(fd, pinned.addressOf(0), 8192u)
            }
            if (n <= 0) break
            chunks.add(buf.copyOf(n.toInt()))
            total += n.toInt()
        }
        ByteArray(total).let { out ->
            var off = 0
            for (chunk in chunks) {
                chunk.copyInto(out, off)
                off += chunk.size
            }
            out.decodeToString()
        }
    } finally {
        close(fd)
    }
}

actual fun getEnvMap(): Map<String, String> {
    val result = mutableMapOf<String, String>()
    val environ = neton_get_environ() ?: return result
    var i = 0
    while (true) {
        val entry = environ[i] ?: break
        val str = entry.toKString()
        val eq = str.indexOf('=')
        if (eq > 0) {
            result[str.substring(0, eq)] = str.substring(eq + 1)
        }
        i++
    }
    return result
}

actual fun getProcessId(): Int = platform.posix.getpid()

actual fun getEnv(key: String): String? {
    val ptr = platform.posix.getenv(key) ?: return null
    return ptr.toKString()
}
