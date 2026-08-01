package neton.core.interfaces

import neton.core.http.HttpContext

/**
 * 限流闸门：由 neton-routing 实现并 bind 进 NetonContext，由 HTTP 适配器在**分发前**调用。
 *
 * 与 [RequestEngine] 同样是「core 定义接口、routing 提供实现、http 消费」的三段式，
 * 目的是让 neton-http 不必依赖 neton-routing。
 *
 * 之所以要有这个接口：`@RateLimit` 的执行点一度只存在于 `DefaultRequestEngine.processRequest`，
 * 而真实分发走的是 HTTP 适配器直接调用 `RouteDefinition.handler`，从不经过那里——
 * 于是注解、KSP 元数据、限流器全都在，唯独没有任何一次真正的限流检查。
 */
interface RateLimitGate {

    /**
     * 检查是否放行。
     *
     * 实现应在拒绝时**自行写好 429 响应**（含 `X-RateLimit-*` / `Retry-After` 头），
     * 调用方只需在返回 false 时跳过业务 handler。
     *
     * @param routeId 限流计数的分组标识，通常是 `controllerClass.methodName`
     * @param identity 当前身份；USER scope 下为 null 时由实现决定放行策略
     * @return true 放行；false 表示已写入 429，调用方必须中止本次分发
     */
    suspend fun allow(
        context: HttpContext,
        routeId: String,
        config: RateLimitConfig,
        identity: Identity?,
    ): Boolean
}
