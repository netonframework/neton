package neton.routing.ratelimit

import neton.core.interfaces.RateLimitCounter
import neton.redis.RedisClient
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/**
 * Redis 限流存储 — 分布式限流
 * 使用 INCR + EXPIRE 实现固定窗口
 */
class RedisRateLimitStore(
    private val redis: RedisClient
) : RateLimitStore {

    override suspend fun incrementAndGet(key: String, windowSeconds: Int): RateLimitCounter {
        val now = Clock.System.now().toEpochSeconds()
        val windowBucket = now / windowSeconds.toLong()
        val redisKey = "ratelimit:$key:$windowBucket"
        val resetAt = (windowBucket + 1) * windowSeconds.toLong()

        val count = redis.incr(redisKey)
        // 仅在第一次时设置过期时间（窗口结束后自动清理）
        if (count == 1L) {
            redis.expire(redisKey, windowSeconds.seconds)
        }
        return RateLimitCounter(count = count, resetAtEpochSeconds = resetAt)
    }
}
