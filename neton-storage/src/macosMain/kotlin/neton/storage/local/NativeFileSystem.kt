@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package neton.storage.local

import kotlinx.cinterop.*
import platform.posix.*

internal actual object NativeFileSystem {

    actual fun readFile(absolutePath: String): ByteArray {
        val fd = open(absolutePath, O_RDONLY)
        if (fd < 0) throw RuntimeException("Cannot open file: $absolutePath")
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
            ByteArray(total).also { out ->
                var off = 0
                for (chunk in chunks) {
                    chunk.copyInto(out, off)
                    off += chunk.size
                }
            }
        } finally {
            close(fd)
        }
    }

    actual fun writeFile(absolutePath: String, data: ByteArray) {
        val slash = absolutePath.lastIndexOf('/')
        if (slash > 0) {
            mkdirs(absolutePath.substring(0, slash))
        }
        @Suppress("EXPERIMENTAL_UNSIGNED_LITERALS")
        val fd = open(absolutePath, O_WRONLY or O_CREAT or O_TRUNC, 420u)
        if (fd < 0) throw RuntimeException("Cannot create file: $absolutePath")
        try {
            data.usePinned { pinned ->
                write(fd, pinned.addressOf(0), data.size.toULong())
            }
            fsync(fd)
        } finally {
            close(fd)
        }
    }

    actual fun deleteFile(absolutePath: String) {
        unlink(absolutePath)
    }

    actual fun fileExists(absolutePath: String): Boolean {
        return memScoped {
            val st = alloc<stat>()
            stat(absolutePath, st.ptr) == 0
        }
    }

    actual fun fileStat(absolutePath: String): NativeFileStat? {
        return memScoped {
            val st = alloc<stat>()
            if (stat(absolutePath, st.ptr) != 0) return null
            NativeFileStat(
                size = st.st_size,
                lastModifiedMs = st.st_mtimespec.tv_sec * 1000L,
                isDirectory = (st.st_mode.toInt() and S_IFDIR) == S_IFDIR
            )
        }
    }

    actual fun listDir(absolutePath: String): List<NativeDirEntry> {
        val dir = opendir(absolutePath) ?: return emptyList()
        val entries = mutableListOf<NativeDirEntry>()
        try {
            while (true) {
                val entry = readdir(dir) ?: break
                val name = entry.pointed.d_name.toKString()
                if (name == "." || name == "..") continue
                val childPath = "$absolutePath/$name"
                val isDir = memScoped {
                    val st = alloc<stat>()
                    if (stat(childPath, st.ptr) == 0) {
                        (st.st_mode.toInt() and S_IFDIR) == S_IFDIR
                    } else false
                }
                entries.add(NativeDirEntry(name, isDir))
            }
        } finally {
            closedir(dir)
        }
        return entries
    }

    actual fun mkdirs(absolutePath: String) {
        var i = 0
        while (i < absolutePath.length) {
            val next = absolutePath.indexOf('/', i)
            val segment = if (next < 0) absolutePath else absolutePath.substring(0, next)
            if (segment.isNotEmpty()) {
                @Suppress("EXPERIMENTAL_UNSIGNED_LITERALS")
                mkdir(segment, 509u) // 0755
            }
            i = if (next < 0) absolutePath.length else next + 1
        }
    }

    actual fun renameFile(src: String, dst: String): Boolean {
        val slash = dst.lastIndexOf('/')
        if (slash > 0) mkdirs(dst.substring(0, slash))
        return platform.posix.rename(src, dst) == 0
    }
}
