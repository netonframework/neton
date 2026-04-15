package neton.routing.ratelimit

import neton.core.interfaces.RateLimitConfig
import neton.core.interfaces.RateLimitDecision
import neton.core.interfaces.RateLimiter

/**
 * 固定窗口限流器 — v1 唯一实现的算法
 */
class FixedWindowRateLimiter(
    private val store: RateLimitStore
) : RateLimiter {

    override suspend fun check(
        routeId: String,
        config: RateLimitConfig,
        identity: String
    ): RateLimitDecision {
        val key = "${routeId}:${config.scope}:${identity}"
        val counter = store.incrementAndGet(key, config.windowSeconds)

        val allowed = counter.count <= config.maxRequests.toLong()
        val remaining = maxOf(0, config.maxRequests - counter.count.toInt())

        return RateLimitDecision(
            allowed = allowed,
            limit = config.maxRequests,
            remaining = remaining,
            resetAtEpochSeconds = counter.resetAtEpochSeconds
        )
    }
}
