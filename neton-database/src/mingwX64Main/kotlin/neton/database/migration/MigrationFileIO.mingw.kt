@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

/*
 * Windows migration file scanning is not implemented in DB-MIG-1.
 * 这是占位 actual,仅保证 mingwX64 target 编译通过。生产 application 部署在
 * macOS / Linux(posixMain 实现)。如未来需要在 Windows 上跑 `application.kexe migrate`,
 * `listDir` 需要按 FindFirstFile / FindNextFile 重写;`readText` / `exists` / `isDirectory`
 * 走 _stat64 / _open / _read 是 OK 的,可继续使用。
 */
package neton.database.migration

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import platform.posix.O_RDONLY
import platform.posix.S_IFDIR
import platform.posix.S_IFMT
import platform.posix._close
import platform.posix._open
import platform.posix._read
import platform.posix._stat64
import platform.posix.time

internal actual object MigrationFileIO {

    actual fun exists(path: String): Boolean = memScoped {
        val st = alloc<_stat64>()
        _stat64(path, st.ptr) == 0
    }

    actual fun isDirectory(path: String): Boolean = memScoped {
        val st = alloc<_stat64>()
        if (_stat64(path, st.ptr) != 0) return false
        (st.st_mode.toInt() and S_IFMT) == S_IFDIR
    }

    actual fun listDir(path: String): List<String> {
        // Windows migration file scanning is not implemented in DB-MIG-1.
        // 目录遍历需 FindFirstFile/FindNextFile; engine 在 Windows 上不实地运行,
        // 仅保证 mingwX64 平台编译通过. 生产部署在 macOS / Linux. WSL 走 posix 路径.
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

internal actual fun migrationCurrentTimeMillis(): Long {
    val nowSec = time(null)
    return nowSec.toLong() * 1000L
}
