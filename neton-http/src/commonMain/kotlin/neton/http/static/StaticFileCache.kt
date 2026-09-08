package neton.http.static

import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * A bounded, disk-following cache of small file contents.
 *
 * Rules it is held to:
 * - **Bounded**: total bytes, entry count and per-file size are all capped. A file
 *   over [maxFileBytes] is never cached — it streams from disk every request.
 * - **Follows the disk**: every request re-stats the file; a cached entry is used
 *   only while size and mtime still match. This is a validation cache, not a TTL.
 *
 * Caveat, stated plainly: identity is (size, mtime-millis). A replacement keeping
 * the exact length AND the exact millisecond is not detected. Atomic replace
 * (write-temp-then-rename) always moves mtime, so the supported update path is
 * safe; a sub-millisecond in-place overwrite of identical length is not.
 *
 * Concurrency: an immutable map behind an [AtomicReference], copy-on-write. Hits —
 * the overwhelming case for static assets — take no lock and copy nothing. A fill
 * or eviction publishes a new map with compareAndSet and retries on contention,
 * which is rare because the working set is small and stable.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class StaticFileCache(
    private val maxTotalBytes: Long = 64L * 1024 * 1024,
    private val maxEntries: Int = 1024,
    private val maxFileBytes: Long = 256L * 1024,
) {
    private class Entry(val bytes: ByteArray, val size: Long, val mtimeMillis: Long)

    private val map = AtomicReference<Map<String, Entry>>(emptyMap())
    val hits = AtomicLong(0)
    val misses = AtomicLong(0)

    /** Fresh bytes for [path] at [stat], reading through on a miss; null if unreadable. */
    fun get(path: String, stat: FileStat): ByteArray? {
        if (stat.size > maxFileBytes) {
            // Too big to cache; stream it. Not counted as hit or miss.
            return StaticFileSystem.readAll(path)
        }
        map.load()[path]?.let { hit ->
            if (hit.size == stat.size && hit.mtimeMillis == stat.mtimeMillis) {
                hits.incrementAndFetch()
                return hit.bytes
            }
        }
        misses.incrementAndFetch()
        val bytes = StaticFileSystem.readAll(path) ?: return null
        publish(path, Entry(bytes, stat.size, stat.mtimeMillis))
        return bytes
    }

    private fun publish(path: String, entry: Entry) {
        while (true) {
            val current = map.load()
            val existing = current[path]
            if (existing != null &&
                existing.size == entry.size && existing.mtimeMillis == entry.mtimeMillis
            ) {
                return // Another request already published an identical entry.
            }
            val next = HashMap(current)
            var total = next.values.sumOf { it.bytes.size.toLong() } - (existing?.bytes?.size?.toLong() ?: 0L)
            if (existing != null) next.remove(path)
            // Bounds: drop everything before inserting if this would overflow. The
            // static working set is small, so a wholesale clear is cheap and rare.
            if (next.size >= maxEntries || total + entry.bytes.size > maxTotalBytes) {
                next.clear(); total = 0
            }
            next[path] = entry
            if (map.compareAndSet(current, next)) return
            // Lost the race; retry against the newly published map.
        }
    }
}
