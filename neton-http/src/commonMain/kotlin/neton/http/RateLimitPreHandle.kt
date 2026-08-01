package neton.http

import neton.core.http.HttpContext
import neton.core.interfaces.Identity
import neton.core.interfaces.RateLimitGate
import neton.core.interfaces.RouteDefinition
import neton.core.interfaces.SecurityAttributes

/**
 * `@RateLimit` 的执行点：在鉴权之后、业务 handler 之前。
 *
 * 与 [runSecurityPreHandle] 一样抽成纯函数，便于契约测试直接驱动。
 *
 * 背景：限流一度只写在 `DefaultRequestEngine.processRequest` 里，而真实分发从不经过那里，
 * 于是 `@RateLimit` 静默失效——注解、KSP 元数据、限流器俱全，却没有任何一次真正的检查。
 * 这个函数就是把执行点搬到活的分发链上。
 *
 * @return true 放行；false 表示 [gate] 已写入 429，调用方必须跳过 handler
 */
internal suspend fun runRateLimitPreHandle(
    route: RouteDefinition,
    httpContext: HttpContext,
    gate: RateLimitGate?,
): Boolean {
    val config = route.rateLimit ?: return true
    // 没装 routing { } 时没有 gate：不限流，但也不该悄悄假装限了
    val activeGate = gate ?: return true
    val identity = httpContext.attributes[SecurityAttributes.IDENTITY] as? Identity
    return activeGate.allow(httpContext, rateLimitRouteId(route), config, identity)
}

/**
 * 限流计数的分组标识。优先用 `controllerClass.methodName`——同一方法在不同挂载点下共享配额；
 * DSL 路由没有 controller 时退回 `METHOD:pattern`。
 */
internal fun rateLimitRouteId(route: RouteDefinition): String =
    route.controllerClass?.let { "$it.${route.methodName}" }
        ?: "${route.method}:${route.pattern}"
