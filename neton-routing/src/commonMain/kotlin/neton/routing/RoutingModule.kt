package neton.routing

import neton.core.interfaces.RequestEngine as CoreRequestEngine
import neton.core.interfaces.RouteDefinition as CoreRouteDefinition
import neton.core.interfaces.RouteHandler as CoreRouteHandler
import neton.routing.engine.*
import neton.routing.matcher.*
import neton.routing.binder.*
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
                "groupNames" to groups.map { it.name }
            )
        )
    }
}

/**
 * 路由组配置
 */
data class RouteGroup(
    val name: String,
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
 * 路由请求引擎适配器 - 将 routing 模块的 RequestEngine 适配为 Core 模块的接口
 */
class RoutingRequestEngineAdapter(
    private val routingEngine: neton.routing.engine.RequestEngine
) : CoreRequestEngine {

    fun setLogger(log: Logger?) {
        (routingEngine as? neton.routing.engine.DefaultRequestEngine)?.setLogger(log)
    }

    override suspend fun processRequest(context: HttpContext): Any? {
        return routingEngine.processRequest(context)
    }

    override fun registerRoute(route: CoreRouteDefinition) {
        // 将 Core 模块的 RouteDefinition 转换为 routing 模块的格式
        val routingRoute = neton.routing.engine.RouteDefinition(
            pattern = route.pattern,
            method = route.method,
            handler = RoutingRouteHandlerAdapter(route.handler),
            parameterBindings = emptyList(), // RESERVED FOR v1.1: 转换参数绑定
            controllerClass = route.controllerClass ?: "Unknown",
            methodName = route.methodName ?: "unknown",
            allowAnonymous = route.allowAnonymous,
            requireAuth = route.requireAuth,
            routeGroup = route.routeGroup,
            permission = route.permission
        )
        routingEngine.registerRoute(routingRoute)
    }

    override fun getRoutes(): List<CoreRouteDefinition> {
        return routingEngine.getRoutes().map { routingRoute ->
            CoreRouteDefinition(
                pattern = routingRoute.pattern,
                method = routingRoute.method,
                handler = CoreRouteHandlerAdapter(routingRoute.handler),
                controllerClass = routingRoute.controllerClass,
                methodName = routingRoute.methodName,
                allowAnonymous = routingRoute.allowAnonymous,
                requireAuth = routingRoute.requireAuth,
                routeGroup = routingRoute.routeGroup,
                permission = routingRoute.permission
            )
        }
    }

    override fun setAuthenticationContext(authContext: AuthenticationContext) {
        // RESERVED FOR v1.1: 认证上下文设置
    }
}

/**
 * 路由处理器适配器 - 将 Core 的 RouteHandler 适配为 routing 模块的接口
 */
class RoutingRouteHandlerAdapter(
    private val coreHandler: CoreRouteHandler
) : neton.routing.engine.RouteHandler {
    override suspend fun invoke(context: HttpContext, args: neton.core.http.HandlerArgs): Any? =
        coreHandler.invoke(context, args)
}

class CoreRouteHandlerAdapter(
    private val routingHandler: neton.routing.engine.RouteHandler
) : CoreRouteHandler {
    override suspend fun invoke(context: HttpContext, args: neton.core.http.HandlerArgs): Any? =
        routingHandler.invoke(context, args)
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
