package neton.routing.ratelimit

import neton.core.interfaces.RateLimitCounter
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

/** Single-process fixed-window counters with sharded locking and expiry indexing. */
class LocalRateLimitStore internal constructor(private val nowSeconds: () -> Long) : RateLimitStore {
    constructor() : this({ Clock.System.now().epochSeconds })
    private data class Key(val name: String, val seconds: Int, val window: Long)
    private data class Bucket(val key: Key, val expiresAt: Long, var count: Long = 0)
    private class Shard {
        val mutex = Mutex()
        val buckets = mutableMapOf<Key, Bucket>()
        val expiry = mutableListOf<Bucket>()

        fun add(bucket: Bucket) {
            buckets[bucket.key] = bucket
            expiry.add(bucket)
            var child = expiry.lastIndex
            while (child > 0) {
                val parent = (child - 1) / 2
                if (expiry[parent].expiresAt <= bucket.expiresAt) break
                expiry[child] = expiry[parent]
                child = parent
            }
            expiry[child] = bucket
        }

        fun removeExpired(now: Long) {
            // Bound cleanup per request; never scan the live counter table.
            repeat(64) {
                val first = expiry.firstOrNull() ?: return
                if (first.expiresAt > now) return
                buckets.remove(first.key)
                val last = expiry.removeAt(expiry.lastIndex)
                if (expiry.isEmpty()) return@repeat
                var parent = 0
                while (parent * 2 + 1 < expiry.size) {
                    var child = parent * 2 + 1
                    if (child + 1 < expiry.size && expiry[child + 1].expiresAt < expiry[child].expiresAt) child++
                    if (last.expiresAt <= expiry[child].expiresAt) break
                    expiry[parent] = expiry[child]
                    parent = child
                }
                expiry[parent] = last
            }
        }
    }

    private val shards = Array(16) { Shard() }

    override suspend fun incrementAndGet(key: String, windowSeconds: Int): RateLimitCounter {
        require(windowSeconds > 0) { "windowSeconds must be positive" }
        val now = nowSeconds()
        val windowBucket = now / windowSeconds.toLong()
        val fullKey = Key(key, windowSeconds, windowBucket)
        val resetAt = (windowBucket + 1) * windowSeconds.toLong()

        val shard = shards[(key.hashCode() and Int.MAX_VALUE) % shards.size]
        return shard.mutex.withLock {
            shard.removeExpired(now)
            val bucket = shard.buckets[fullKey] ?: Bucket(fullKey, resetAt).also(shard::add)
            bucket.count++

            RateLimitCounter(
                count = bucket.count,
                resetAtEpochSeconds = bucket.expiresAt
            )
        }
    }
}
