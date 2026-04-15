package neton.routing.ratelimit

import neton.core.interfaces.RateLimitCounter
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

/**
 * 本地内存限流存储 — 单机限流（无 Redis 时降级使用）
 * 使用 Mutex + Map 存储计数器
 */
class LocalRateLimitStore : RateLimitStore {

    private data class Bucket(
        var count: Long,
        var expiresAt: Long
    )

    private val buckets = mutableMapOf<String, Bucket>()
    private val mutex = Mutex()

    override suspend fun incrementAndGet(key: String, windowSeconds: Int): RateLimitCounter {
        val now = Clock.System.now().toEpochSeconds()
        val windowBucket = now / windowSeconds.toLong()
        val fullKey = "$key:$windowBucket"
        val resetAt = (windowBucket + 1) * windowSeconds.toLong()

        return mutex.withLock {
            // 清理过期 key
            val iterator = buckets.iterator()
            while (iterator.hasNext()) {
                val (_, bucket) = iterator.next()
                if (bucket.expiresAt < now) {
                    iterator.remove()
                }
            }

            val bucket = buckets.getOrPut(fullKey) {
                Bucket(0, resetAt)
            }
            bucket.count++

            RateLimitCounter(
                count = bucket.count,
                resetAtEpochSeconds = bucket.expiresAt
            )
        }
    }
}
