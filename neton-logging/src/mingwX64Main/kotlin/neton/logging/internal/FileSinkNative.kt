@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package neton.logging.internal

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.posix.O_APPEND
import platform.posix.O_CREAT
import platform.posix.O_WRONLY
import platform.posix._commit
import platform.posix.close
import platform.posix.mkdir
import platform.posix.open
import platform.posix.write

/**
 * Windows 文件 Sink：MinGW 追加写。
 */
/**
 * Windows 侧只对齐签名，不做归档与清理：生产部署是 Linux（posixMain 那份实现了按天归档
 * + 保留窗口清理）。这里留着 [retentionDays] 是为了 expect/actual 一致，等 Windows 真要
 * 上生产时再补——假装实现了比明说没实现更糟。
 */
internal class FileSinkNative(
    private val path: String,
    @Suppress("unused") private val retentionDays: Int = LogRetention.DEFAULT_RETENTION_DAYS,
) : Sink {

    private var fd: Int = -1

    private fun ensureParentDir() {
        val slash = path.lastIndexOf('/')
        if (slash <= 0) return
        val dir = path.substring(0, slash)
        var i = 0
        while (i < dir.length) {
            val next = dir.indexOf('/', i)
            val segment = if (next < 0) dir else dir.substring(0, next)
            if (segment.isNotEmpty()) {
                mkdir(segment) // MinGW mkdir 无 mode 参数
            }
            i = if (next < 0) dir.length else next + 1
        }
    }

    private fun ensureOpen(): Int {
        if (fd < 0) {
            ensureParentDir()
            @Suppress("EXPERIMENTAL_UNSIGNED_LITERALS")
            fd = open(path, O_WRONLY or O_CREAT or O_APPEND, 420) // MinGW mode 为 int
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
            write(f, pinned.addressOf(0), bytes.size.toUInt())
        }
    }

    override fun flush() {
        if (fd >= 0) _commit(fd)
    }

    override fun close() {
        if (fd >= 0) {
            _commit(fd)
            close(fd)
            fd = -1
        }
    }
}
