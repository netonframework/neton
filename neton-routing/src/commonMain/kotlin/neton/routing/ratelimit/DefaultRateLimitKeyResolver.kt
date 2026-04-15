package neton.routing.ratelimit

import neton.core.annotations.RateLimitScope

/**
 * 默认限流 key 解析器
 * 支持 USER / IP / GLOBAL / CUSTOM 四种 scope
 * CUSTOM 仅支持 query:/header:/path: 前缀
 */
class DefaultRateLimitKeyResolver : RateLimitKeyResolver {

    override suspend fun resolve(
        routeId: String,
        scope: RateLimitScope,
        key: String,
        context: RateLimitRequestContext
    ): String? {
        return when (scope) {
            RateLimitScope.USER -> context.userId ?: return null
            RateLimitScope.IP -> context.remoteIp
            RateLimitScope.GLOBAL -> "global"
            RateLimitScope.CUSTOM -> resolveCustomKey(key, context)
        }
    }

    private fun resolveCustomKey(key: String, context: RateLimitRequestContext): String? {
        return when {
            key.startsWith("query:") -> {
                val paramName = key.removePrefix("query:")
                context.queryParam(paramName)
            }
            key.startsWith("header:") -> {
                val headerName = key.removePrefix("header:")
                context.header(headerName)
            }
            key.startsWith("path:") -> {
                val paramName = key.removePrefix("path:")
                context.pathParam(paramName)
            }
            else -> key  // 自定义 key 直接使用
        }
    }
}
