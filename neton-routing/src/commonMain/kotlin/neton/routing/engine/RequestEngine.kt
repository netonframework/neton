package neton.routing.engine

import neton.core.http.HttpMethod
import neton.core.http.HttpContext
import kotlin.reflect.KClass

/**
 * 请求处理引擎接口 - 专注于路由匹配和参数绑定
 *
 * 使用 core 模块的 HTTP 接口定义
 */
interface RequestEngine {

    /**
     * 处理请求
     */
    suspend fun processRequest(context: HttpContext): Any?

    /**
     * 注册路由定义
     */
    fun registerRoute(route: RouteDefinition)

    /**
     * 获取所有注册的路由
     */
    fun getRoutes(): List<RouteDefinition>
}

/**
 * 路由定义
 */
data class RouteDefinition(
    val pattern: String,                           // 路由模式，如 "/user/{id}/{status}"
    val method: HttpMethod,                        // HTTP 方法
    val handler: RouteHandler,                     // 方法调用处理器
    val parameterBindings: List<ParameterBinding>, // 参数绑定配置
    val controllerClass: String,                   // 控制器类名
    val methodName: String,                        // 方法名
    val allowAnonymous: Boolean = false,           // 是否允许匿名访问（@AllowAnonymous）
    val requireAuth: Boolean = false,              // 是否需认证（@RequireAuth）
    val routeGroup: String? = null,                // 路由组（仅当包最后段命中 RoutingConfig.groups 时）
    val permission: String? = null                 // @Permission 值
)

/**
 * 路由匹配结果
 */
data class RouteMatch(
    val route: RouteDefinition,                    // 匹配的路由定义
    val pathParameters: Map<String, String>        // 提取的路径参数
)

/**
 * 方法调用处理器
 */
interface RouteHandler {
    suspend fun invoke(context: HttpContext, args: neton.core.http.HandlerArgs): Any?
}

/**
 * 参数绑定配置 - 不依赖具体HTTP类型
 */
sealed class ParameterBinding {
    abstract val parameterName: String      // 方法参数名
    abstract val parameterType: KClass<*>   // 参数类型

    /**
     * 路径参数绑定
     */
    data class PathVariable(
        override val parameterName: String,
        val pathKey: String,                 // 路径中的占位符名称
        override val parameterType: KClass<*>
    ) : ParameterBinding()

    /**
     * 请求体绑定
     */
    data class RequestBody(
        override val parameterName: String,
        override val parameterType: KClass<*>
    ) : ParameterBinding()

    /**
     * 当前用户绑定
     */
    data class CurrentUser(
        override val parameterName: String,
        override val parameterType: KClass<*>,
        val required: Boolean = true
    ) : ParameterBinding()

    /**
     * 上下文对象绑定
     */
    data class ContextObject(
        override val parameterName: String,
        override val parameterType: KClass<*>
    ) : ParameterBinding()
}

/**
 * 请求处理异常
 */
sealed class RequestProcessingException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /**
     * 路由未找到异常
     */
    class RouteNotFoundException(val path: String, val method: HttpMethod) :
        RequestProcessingException("No route found for $method $path")

    /**
     * 参数绑定异常
     */
    class ParameterBindingException(parameterName: String, cause: Throwable) :
        RequestProcessingException("Failed to bind parameter '$parameterName'", cause)

    /**
     * 方法调用异常
     */
    class MethodInvocationException(methodName: String, cause: Throwable) :
        RequestProcessingException("Failed to invoke method '$methodName'", cause)

    /**
     * 响应序列化异常
     */
    class ResponseSerializationException(cause: Throwable) :
        RequestProcessingException("Failed to serialize response", cause)
}
