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
) {
    private val mutex = Mutex()
    private val map = mutableMapOf<K, L1Entry<V>>()
    private val order = ArrayDeque<K>(maxSize.coerceAtLeast(16))

    private class L1Entry<V>(val value: V, val expiresAt: Long)

    suspend fun get(key: K): V? = mutex.withLock {
        val e = map[key] ?: return null
        if (e.expiresAt >= nowMs()) e.value else { map.remove(key); order.remove(key); null }
    }

    suspend fun put(key: K, value: V, ttl: Duration) = mutex.withLock {
        while (map.size >= maxSize && order.isNotEmpty()) {
            val oldest = order.removeFirst()
            map.remove(oldest)
        }
        val expiresAt = nowMs() + ttl.inWholeMilliseconds
        map[key] = L1Entry(value, expiresAt)
        order.remove(key)
        order.addLast(key)
    }

    suspend fun delete(key: K) = mutex.withLock { map.remove(key); order.remove(key) }

    suspend fun clear() = mutex.withLock { map.clear(); order.clear() }

    private fun nowMs(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()
}
