package neton.routing.engine

import neton.logging.Logger

/**
 * 路由注册表。
 *
 * **它不分发请求**：HTTP 适配器（[neton.http.KtorHttpAdapter]）从 [getRoutes] 拿到路由后
 * 直接调用 [RouteDefinition.handler]（KSP 编译期生成的 lambda，自带参数绑定与序列化）。
 * 因此这里只负责「登记 + 去重 + 供查询」。
 *
 * 历史说明：本类曾带一条完整的 `processRequest` 分发链（路由匹配 → 限流 → 参数绑定 →
 * 响应序列化），但从未被任何生产路径调用；限流真正的执行点现在是
 * [neton.core.interfaces.RateLimitGate]，由适配器在分发前调用。
 */
class DefaultRequestEngine : RequestEngine {

    private var logger: Logger? = null
    private val routes = mutableListOf<RouteDefinition>()

    fun setLogger(log: Logger?) {
        logger = log
    }

    override fun registerRoute(route: RouteDefinition) {
        // 相同 path 在 app/admin 等不同组下是各自独立的（挂载时按组加前缀），
        // 只有同一逻辑组内的重复注册才算冲突。
        // routeGroup 由 KSP 编译期写入（目录约定）或 DSL group() 注入；运行时不解析类名。
        val existingRoute = routes.find {
            it.pattern == route.pattern &&
                it.method == route.method &&
                it.routeGroup == route.routeGroup
        }
        if (existingRoute != null) {
            logger?.warn(
                "routing.route.duplicate",
                mapOf(
                    "method" to route.method.name,
                    "pattern" to route.pattern,
                    "existingController" to (existingRoute.controllerClass ?: ""),
                    "newController" to (route.controllerClass ?: "")
                )
            )
            return
        }

        routes.add(route)
        logger?.info(
            "routing.route.registered",
            mapOf(
                "method" to route.method.name,
                "pattern" to route.pattern,
                "controllerClass" to route.controllerClass,
                "methodName" to route.methodName
            )
        )
    }

    override fun getRoutes(): List<RouteDefinition> = routes.toList()
}
