package neton.routing.ratelimit

import neton.core.http.HttpContext
import neton.core.http.HttpStatus
import neton.core.interfaces.Identity
import neton.core.interfaces.RateLimitConfig
import neton.core.interfaces.RateLimitDecision
import neton.core.interfaces.RateLimitGate
import neton.core.interfaces.RateLimiter

/**
 * 限流拦截器 — 挂在路由匹配之后、handler 调用之前
 * 检查 route 上的 rateLimit 配置，决定是否放行
 *
 * 实现 [RateLimitGate]：由 `routing { }` bind 进 NetonContext，HTTP 适配器在分发前调用。
 */
class RateLimitInterceptor(
    private val limiter: RateLimiter,
    private val resolver: RateLimitKeyResolver
) : RateLimitGate {

    /**
     * 处理限流检查。返回 true 表示放行，false 表示已返回 429 响应。
     */
    override suspend fun allow(
        context: HttpContext,
        routeId: String,
        config: RateLimitConfig,
        identity: Identity?
    ): Boolean {
        val key = resolver.resolve(
            routeId = routeId,
            scope = config.scope,
            key = config.key,
            context = HttpContextRateLimitContext(context, identity)
        )

        if (key == null) {
            // 无法解析 key（如 USER scope 但未登录），默认放行
            return true
        }

        val decision = limiter.check(routeId, config, key)

        // 设置限流响应头
        context.response.header("X-RateLimit-Limit", config.maxRequests.toString())
        context.response.header("X-RateLimit-Remaining", maxOf(0, decision.remaining).toString())
        context.response.header("X-RateLimit-Reset", decision.resetAtEpochSeconds.toString())

        if (!decision.allowed) {
            context.response.header("Retry-After", config.windowSeconds.toString())
            context.response.status = HttpStatus.TOO_MANY_REQUESTS
            context.response.json(
                mapOf(
                    "code" to 429,
                    "message" to config.message
                )
            )
            return false
        }

        return true
    }

    /**
     * 从 HttpContext 提取限流上下文
     */
    private class HttpContextRateLimitContext(
        private val context: HttpContext,
        private val identity: Identity?
    ) : RateLimitRequestContext {
        override val userId: String? get() = identity?.id?.toString()
        override val remoteIp: String get() = context.request.header("X-Forwarded-For")
            ?: context.request.header("X-Real-IP")
            ?: "127.0.0.1"

        override fun queryParam(name: String): String? = context.request.queryParam(name)
        override fun header(name: String): String? = context.request.header(name)
        override fun pathParam(name: String): String? = context.getAttribute("pathParam:$name") as? String
    }
}
