package neton.cache.internal

import kotlin.time.Duration
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 进程内 LRU + TTL；maxSize 上限；L1 TTL 不得长于 L2（由调用方保证）。
 */
internal class L1Cache<K, V>(
    private val maxSize: Int,
    private val defaultTtl: Duration,
    private val nowMs: () -> Long = run {
        val origin = kotlin.time.TimeSource.Monotonic.markNow()
        val clock: () -> Long = { origin.elapsedNow().inWholeMilliseconds }
        clock
    },
) {
    init { require(maxSize > 0) }
    private val mutex = Mutex()
    private val map = linkedMapOf<K, L1Entry<V>>()

    private class L1Entry<V>(val value: V, val expiresAt: Long)

    suspend fun get(key: K): V? = mutex.withLock {
        val e = map.remove(key) ?: return null
        if (e.expiresAt <= nowMs()) return null
        map[key] = e
        e.value
    }

    suspend fun put(key: K, value: V, ttl: Duration) = mutex.withLock {
        map.remove(key)
        if (ttl <= Duration.ZERO) return@withLock
        while (map.size >= maxSize) {
            map.remove(map.keys.first())
        }
        val now = nowMs()
        val millis = ttl.inWholeMilliseconds
        val expiresAt = if (millis > Long.MAX_VALUE - now) Long.MAX_VALUE else now + millis
        map[key] = L1Entry(value, expiresAt)
    }

    suspend fun delete(key: K) = mutex.withLock { map.remove(key); Unit }

    suspend fun clear() = mutex.withLock { map.clear() }
}
