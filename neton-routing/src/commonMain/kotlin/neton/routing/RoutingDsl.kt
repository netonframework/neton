package neton.routing

import neton.core.interfaces.RequestEngine
import neton.core.interfaces.RouteDefinition
import neton.core.interfaces.RouteHandler
import neton.core.http.HttpContext
import neton.core.http.HttpMethod
import neton.core.http.HandlerArgs

/**
 * 极简路由 DSL - 无需 KSP、无需 Controller、Native-first
 *
 * 层级分离：
 * - RequestEngine.registerRoute → 框架 SPI
 * - routing DSL get/post/put/delete/group → 框架 DX（本层）
 * - @Controller + KSP → 编译期高阶模式
 *
 * v1.1 冻结：neton-routing 必须提供最小 DSL，无需 KSP 即可定义路由。
 *
 * Handler 仅暴露 HttpContext（不暴露 HandlerArgs，那是 KSP/参数绑定器内部 SPI）。
 * HttpContext 已包含 request、response、pathParams、queryParams、session、attributes、applicationContext。
 */

// --- RequestEngine 顶层：无 prefix、无 routeGroup ---

fun RequestEngine.get(path: String, handler: suspend (HttpContext) -> Any?) {
    registerRoute(RouteDefinition(
        pattern = path,
        method = HttpMethod.GET,
        handler = HttpContextHandler(handler)
    ))
}

fun RequestEngine.post(path: String, handler: suspend (HttpContext) -> Any?) {
    registerRoute(RouteDefinition(
        pattern = path,
        method = HttpMethod.POST,
        handler = HttpContextHandler(handler)
    ))
}

fun RequestEngine.put(path: String, handler: suspend (HttpContext) -> Any?) {
    registerRoute(RouteDefinition(
        pattern = path,
        method = HttpMethod.PUT,
        handler = HttpContextHandler(handler)
    ))
}

fun RequestEngine.delete(path: String, handler: suspend (HttpContext) -> Any?) {
    registerRoute(RouteDefinition(
        pattern = path,
        method = HttpMethod.DELETE,
        handler = HttpContextHandler(handler)
    ))
}

// --- group DSL：prefix 叠加 + routeGroup 注入 Security ---

/**
 * 路由组：将 prefix 合并进 pattern，routeGroup 供 Security 管道使用。
 * 支持嵌套：group("admin") { group("v1") { get("/users") } } → /admin/v1/users
 * routeGroup 取最外层（DSL group > KSP routeGroup > runtime infer）
 */
fun RequestEngine.group(name: String, block: RouteGroupScope.() -> Unit) {
    RouteGroupScope(this, name, "/$name", routeGroup = name).block()
}

/**
 * 路由组作用域：prefix 叠加、routeGroup 透传
 */
class RouteGroupScope(
    private val engine: RequestEngine,
    private val groupName: String,
    private val prefix: String,
    private val routeGroup: String
) {
    private fun joinPath(path: String): String {
        val p = if (path.startsWith("/")) path else "/$path"
        val pre = prefix.trimEnd('/')
        return if (pre.isEmpty()) p else "$pre$p"
    }

    fun get(path: String, handler: suspend (HttpContext) -> Any?) {
        engine.registerRoute(RouteDefinition(
            pattern = joinPath(path),
            method = HttpMethod.GET,
            handler = HttpContextHandler(handler),
            routeGroup = routeGroup
        ))
    }

    fun post(path: String, handler: suspend (HttpContext) -> Any?) {
        engine.registerRoute(RouteDefinition(
            pattern = joinPath(path),
            method = HttpMethod.POST,
            handler = HttpContextHandler(handler),
            routeGroup = routeGroup
        ))
    }

    fun put(path: String, handler: suspend (HttpContext) -> Any?) {
        engine.registerRoute(RouteDefinition(
            pattern = joinPath(path),
            method = HttpMethod.PUT,
            handler = HttpContextHandler(handler),
            routeGroup = routeGroup
        ))
    }

    fun delete(path: String, handler: suspend (HttpContext) -> Any?) {
        engine.registerRoute(RouteDefinition(
            pattern = joinPath(path),
            method = HttpMethod.DELETE,
            handler = HttpContextHandler(handler),
            routeGroup = routeGroup
        ))
    }

    /**
     * 嵌套 group：prefix 叠加，routeGroup 保持最外层
     */
    fun group(name: String, block: RouteGroupScope.() -> Unit) {
        val newPrefix = joinPath("/$name")
        RouteGroupScope(engine, name, newPrefix, routeGroup = routeGroup).block()
    }
}

private class HttpContextHandler(
    private val handler: suspend (HttpContext) -> Any?
) : RouteHandler {
    override suspend fun invoke(context: HttpContext, args: HandlerArgs): Any? =
        handler(context)
}
