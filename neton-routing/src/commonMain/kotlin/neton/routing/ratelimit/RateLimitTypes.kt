package neton.routing.ratelimit

import neton.core.annotations.RateLimitScope
import neton.core.interfaces.RateLimitCounter

/**
 * 限流存储接口 — Redis / Local 双实现
 */
interface RateLimitStore {
    suspend fun incrementAndGet(
        key: String,
        windowSeconds: Int
    ): RateLimitCounter
}

/**
 * 限流 key 解析器
 */
interface RateLimitKeyResolver {
    suspend fun resolve(
        routeId: String,
        scope: RateLimitScope,
        key: String,
        context: RateLimitRequestContext
    ): String?
}

/**
 * 限流请求上下文 — 供 key resolver 使用
 */
interface RateLimitRequestContext {
    val userId: String?
    val remoteIp: String
    fun queryParam(name: String): String?
    fun header(name: String): String?
    fun pathParam(name: String): String?
}
