package neton.routing

import neton.core.interfaces.RequestEngine as CoreRequestEngine
import neton.core.interfaces.RouteDefinition
import neton.core.interfaces.RouteHandler
import neton.routing.engine.*
import neton.core.http.HttpContext
import neton.core.http.HttpMethod
import neton.core.security.AuthenticationContext
import neton.logging.Logger

/**
 * 路由配置数据类 - RoutingComponent 专用
 */
data class RoutingConfig(
    val debug: Boolean = false,
    val groups: List<RouteGroup> = emptyList()
) {
    fun logSummary() {
        RoutingLog.log?.info(
            "routing.config.summary",
            mapOf(
                "debug" to debug,
                "groups" to groups.size,
                "groupNames" to groups.map { it.group }
            )
        )
    }
}

/**
 * 路由组配置
 */
data class RouteGroup(
    val group: String,
    val mount: RouteMountConfig,
    val requireAuth: Boolean = false,
    val allowAnonymous: List<String> = emptyList()
)

/**
 * 路由挂载配置
 */
data class RouteMountConfig(
    val type: RouteMountType,
    val value: String
)

/**
 * 路由挂载类型
 */
enum class RouteMountType {
    PATH,    // 路径挂载：/admin
    DOMAIN   // 域名挂载：admin.example.com
}

/**
 * 路由请求引擎适配器 — routing engine → core RequestEngine
 *
 * 模型已统一（RouteDefinition / RouteHandler 均为 core 类型的 typealias），
 * adapter 仅做接口桥接，不再做字段逐一转换。
 */
class RoutingRequestEngineAdapter(
    private val routingEngine: neton.routing.engine.RequestEngine
) : CoreRequestEngine {

    fun setLogger(log: Logger?) {
        (routingEngine as? neton.routing.engine.DefaultRequestEngine)?.setLogger(log)
    }

    fun setRateLimitInterceptor(interceptor: neton.routing.ratelimit.RateLimitInterceptor) {
        (routingEngine as? neton.routing.engine.DefaultRequestEngine)?.setRateLimitInterceptor(interceptor)
    }

    override suspend fun processRequest(context: HttpContext): Any? {
        return routingEngine.processRequest(context)
    }

    override fun registerRoute(route: RouteDefinition) {
        routingEngine.registerRoute(route)
    }

    override fun getRoutes(): List<RouteDefinition> {
        return routingEngine.getRoutes()
    }

    override fun setAuthenticationContext(authContext: AuthenticationContext) {
        // RESERVED FOR v1.1: 认证上下文设置
    }
}

/**
 * 路由统计信息
 */
data class RouteStatistics(
    val totalRoutes: Int,
    val routesByMethod: Map<HttpMethod, Int>,
    val routesByController: Map<String, Int>,
    val controllersCount: Int
) {
    fun logSummary() {
        RoutingLog.log?.info(
            "routing.stats",
            mapOf(
                "totalRoutes" to totalRoutes,
                "controllersCount" to controllersCount,
                "routesByMethod" to routesByMethod,
                "routesByController" to routesByController
            )
        )
    }
}
