package neton.routing.engine

/**
 * 统一模型：routing 层直接使用 core 定义的 RouteDefinition / RouteMatch / RouteHandler 等。
 * 不再维护本地副本，避免 adapter 层字段丢失。
 *
 * 以下 typealias 保持 engine 包内已有 import 路径不变。
 */

typealias RouteDefinition = neton.core.interfaces.RouteDefinition
typealias RouteMatch = neton.core.interfaces.RouteMatch
typealias RouteHandler = neton.core.interfaces.RouteHandler
typealias ParameterBinding = neton.core.interfaces.ParameterBinding
typealias RequestProcessingException = neton.core.interfaces.RequestProcessingException

/**
 * 路由注册表接口 — routing 层保留自己的 interface（core 版另有 adapter 包装）。
 *
 * 只登记与查询：请求分发由 HTTP 适配器直接调用 RouteDefinition.handler 完成。
 */
interface RequestEngine {

    /**
     * 注册路由定义
     */
    fun registerRoute(route: RouteDefinition)

    /**
     * 获取所有注册的路由
     */
    fun getRoutes(): List<RouteDefinition>
}
