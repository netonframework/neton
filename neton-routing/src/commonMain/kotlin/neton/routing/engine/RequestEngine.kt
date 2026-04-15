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
 * 请求处理引擎接口 — routing 层仍保留自己的 interface
 * 与 core 的 RequestEngine 区别：不含 setAuthenticationContext
 */
interface RequestEngine {

    /**
     * 处理请求
     */
    suspend fun processRequest(context: neton.core.http.HttpContext): Any?

    /**
     * 注册路由定义
     */
    fun registerRoute(route: RouteDefinition)

    /**
     * 获取所有注册的路由
     */
    fun getRoutes(): List<RouteDefinition>
}
