@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package neton.logging.internal

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.posix.O_APPEND
import platform.posix.O_CREAT
import platform.posix.O_WRONLY
import platform.posix.close
import platform.posix.fsync
import platform.posix.mkdir
import platform.posix.open
import platform.posix.write

/**
 * Native-only 文件 Sink：POSIX 追加写、单行 \n 结尾（v1 冻结）。
 * 若 path 含目录（如 logs/access.log），首次 open 前确保父目录存在。
 */
internal class FileSinkNative(private val path: String) : Sink {

    private var fd: Int = -1

    private fun ensureParentDir() {
        val slash = path.lastIndexOf('/')
        if (slash <= 0) return
        val dir = path.substring(0, slash)
        // 递归创建父目录（如 logs/sub/）
        var i = 0
        while (i < dir.length) {
            val next = dir.indexOf('/', i)
            val segment = if (next < 0) dir else dir.substring(0, next)
            if (segment.isNotEmpty()) {
                @Suppress("EXPERIMENTAL_UNSIGNED_LITERALS")
                mkdir(segment, 509u) // 0755; 已存在则 EEXIST 忽略
            }
            i = if (next < 0) dir.length else next + 1
        }
    }

    private fun ensureOpen(): Int {
        if (fd < 0) {
            ensureParentDir()
            @Suppress("EXPERIMENTAL_UNSIGNED_LITERALS")
            fd = open(path, O_WRONLY or O_CREAT or O_APPEND, 420u)
            if (fd < 0) return fd
        }
        return fd
    }

    override fun writeLine(line: String) {
        writeLines(listOf(line))
    }

    override fun writeLines(batch: List<String>) {
        val f = ensureOpen()
        if (f < 0) return
        if (batch.isEmpty()) return
        val combined = batch.joinToString("") { line -> if (line.endsWith("\n")) line else "$line\n" }
        val bytes = combined.encodeToByteArray()
        bytes.usePinned { pinned ->
            write(f, pinned.addressOf(0), bytes.size.toULong())
        }
    }

    override fun flush() {
        if (fd >= 0) fsync(fd)
    }

    override fun close() {
        if (fd >= 0) {
            fsync(fd)
            close(fd)
            fd = -1
        }
    }
}
