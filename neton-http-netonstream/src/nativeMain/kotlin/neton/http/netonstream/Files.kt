@file:OptIn(ExperimentalForeignApi::class)

package neton.http.netonstream

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell

/** The bytes of the file at [path]; throws [IllegalStateException] naming the path when it cannot be read. */
// C long is 32-bit on Windows and 64-bit on POSIX; convert before applying our 4 MiB bound.
@OptIn(UnsafeNumber::class)
internal fun readFileBytes(path: String): ByteArray {
    val file = fopen(path, "rb") ?: error("cannot open $path")
    try {
        check(fseek(file, 0.convert(), SEEK_END) == 0) { "cannot seek $path" }
        val size = ftell(file).convert<Long>()
        check(size in 0..(4L * 1024 * 1024)) { "unexpected size $size for $path" }
        check(fseek(file, 0.convert(), SEEK_SET) == 0) { "cannot seek $path" }
        val bytes = ByteArray(size.toInt())
        if (bytes.isEmpty()) return bytes
        val read = bytes.usePinned { fread(it.addressOf(0), 1u.convert(), bytes.size.convert(), file) }.toLong()
        check(read == size) { "short read of $path: $read of $size bytes" }
        return bytes
    } finally {
        fclose(file)
    }
}
