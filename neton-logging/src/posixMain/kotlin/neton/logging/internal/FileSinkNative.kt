@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package neton.logging.internal

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.posix.O_APPEND
import platform.posix.O_CREAT
import platform.posix.O_WRONLY
import platform.posix.close
import platform.posix.closedir
import platform.posix.fsync
import platform.posix.localtime_r
import platform.posix.opendir
import platform.posix.open
import platform.posix.readdir
import platform.posix.remove
import platform.posix.rename
import platform.posix.time_tVar
import platform.posix.tm
import platform.posix.write

/**
 * Native-only 文件 Sink：POSIX 追加写、单行 \n 结尾（v1 冻结）。
 * 若 path 含目录（如 logs/access.log），首次 open 前确保父目录存在。
 *
 * 按天归档 + 过期清理：跨天后把 `access.log` 改名为 `access.log.YYYY-MM-DD`，并删掉超出保留
 * 窗口的归档。命名与保留语义跟 privchat-server 完全一致（保留最近 N 天、含今天）。
 *
 * 不这么做的后果实测过：这个 sink 原本只追加不轮转，生产上 `all.log` 涨到 847MB、
 * `error.log` 434MB，磁盘上没有任何机制会去收它们。
 *
 * @param retentionDays 保留天数；<= 0 关闭清理（仍然按天归档）
 */
internal class FileSinkNative(
    private val path: String,
    private val retentionDays: Int = LogRetention.DEFAULT_RETENTION_DAYS,
) : Sink {

    private var fd: Int = -1

    /** 当前归档窗口所属日期，形如 20260807；-1 表示还没开过文件 */
    private var currentDateKey: Int = -1

    private val dir: String = path.substringBeforeLast('/', "")
    private val baseName: String = path.substringAfterLast('/')

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
                createDirWithMode(segment) // 0755; 已存在则 EEXIST 忽略
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
            if (currentDateKey < 0) {
                currentDateKey = todayKey()
                // 启动时扫一次：进程可能停了很久，期间没人清理过。
                purgeExpired(currentDateKey)
            }
        }
        return fd
    }

    /**
     * 跨天则归档当前文件。写入路径上调用，所以失败一律吞掉——归档不了是一回事，因为归档
     * 失败把这条日志也丢了是另一回事。
     */
    private fun rollIfDayChanged() {
        if (fd < 0) return
        val today = todayKey()
        if (today == currentDateKey) return

        fsync(fd)
        close(fd)
        fd = -1

        val archived = archivePathFor(currentDateKey)
        if (archived != null) {
            rename(path, archived)
        }
        currentDateKey = today
        purgeExpired(today)
    }

    /** 同一天多次归档（重启等）时追加 `.1` `.2`，不覆盖已有文件。 */
    private fun archivePathFor(dateKey: Int): String? {
        if (dateKey <= 0) return null
        val stamp = formatDateKey(dateKey)
        val first = "$path.$stamp"
        if (!fileExists(first)) return first
        var idx = 1
        while (idx < 1000) {
            val candidate = "$path.$stamp.$idx"
            if (!fileExists(candidate)) return candidate
            idx++
        }
        return null
    }

    private fun purgeExpired(todayKey: Int) {
        if (retentionDays <= 0) return
        val cutoff = cutoffKey(todayKey, retentionDays)
        val listing = listDir(if (dir.isEmpty()) "." else dir)
        val doomed = LogRetention.selectExpired(listing, baseName, cutoff, retentionDays)
        for (name in doomed) {
            val full = if (dir.isEmpty()) name else "$dir/$name"
            remove(full)
        }
    }

    override fun writeLine(line: String) {
        writeLines(listOf(line))
    }

    override fun writeLines(batch: List<String>) {
        val f = ensureOpen()
        if (f < 0) return
        if (batch.isEmpty()) return
        rollIfDayChanged()
        val target = ensureOpen()
        if (target < 0) return
        val combined = batch.joinToString("") { line -> if (line.endsWith("\n")) line else "$line\n" }
        val bytes = combined.encodeToByteArray()
        bytes.usePinned { pinned ->
            write(target, pinned.addressOf(0), bytes.size.toULong())
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

/** 本地日期，形如 20260807。 */
private fun todayKey(): Int = memScoped {
    val t = alloc<time_tVar>()
    t.value = platform.posix.time(null)
    val out = alloc<tm>()
    localtime_r(t.ptr, out.ptr)
    (out.tm_year + 1900) * 10000 + (out.tm_mon + 1) * 100 + out.tm_mday
}

private fun formatDateKey(key: Int): String {
    val y = key / 10000
    val m = (key / 100) % 100
    val d = key % 100
    val mm = if (m < 10) "0$m" else "$m"
    val dd = if (d < 10) "0$d" else "$d"
    return "$y-$mm-$dd"
}

/**
 * 保留窗口的第一天：今天往前数 `retentionDays - 1` 天。
 *
 * 用 `mktime` 做日期回退，天然处理跨月跨年——手写「减去 N 天」在月初和闰年上必错。
 */
private fun cutoffKey(todayKey: Int, retentionDays: Int): Int = memScoped {
    val out = alloc<tm>()
    out.tm_year = todayKey / 10000 - 1900
    out.tm_mon = (todayKey / 100) % 100 - 1
    out.tm_mday = todayKey % 100 - (retentionDays - 1)
    out.tm_hour = 12 // 正午，避开 DST 切换那一小时
    platform.posix.mktime(out.ptr)
    (out.tm_year + 1900) * 10000 + (out.tm_mon + 1) * 100 + out.tm_mday
}

private fun fileExists(p: String): Boolean = platform.posix.access(p, platform.posix.F_OK) == 0

private fun listDir(d: String): List<String> {
    val handle = opendir(d) ?: return emptyList()
    val names = mutableListOf<String>()
    try {
        while (true) {
            val entry = readdir(handle) ?: break
            names.add(entry.pointed.d_name.toKString())
        }
    } finally {
        closedir(handle)
    }
    return names
}
