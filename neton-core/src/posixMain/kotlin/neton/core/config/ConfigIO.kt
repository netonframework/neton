@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package neton.core.config

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
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
    // TODO: 正确遍历 environ 需 CPointer 兼容，当前 cinterop 类型有兼容性差异，先返回空
    // 不影响 CLI/文件配置加载，仅 ENV 覆盖暂不可用
    return emptyMap()
}

actual fun getProcessId(): Int = platform.posix.getpid()

actual fun getEnv(key: String): String? {
    val ptr = platform.posix.getenv(key) ?: return null
    return ptr.toKString()
}
