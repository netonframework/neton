package neton.routing.engine

import neton.routing.matcher.DefaultRouteMatcher
import neton.routing.matcher.PathPatternUtils
import neton.routing.matcher.RouteMatcher
import neton.routing.binder.DefaultParameterBinder
import neton.routing.binder.ParameterBinder
import neton.core.http.HttpContext
import neton.core.http.HttpStatus
import neton.logging.Logger

/**
 * 默认请求处理引擎实现
 *
 * 整合路由匹配、参数绑定和方法调用的完整流程
 */
class DefaultRequestEngine(
    private val routeMatcher: RouteMatcher = DefaultRouteMatcher(),
    private val parameterBinder: ParameterBinder = DefaultParameterBinder()
) : RequestEngine {

    private var logger: Logger? = null

    fun setLogger(log: Logger?) {
        logger = log
    }

    private val routes = mutableListOf<RouteDefinition>()
    
    override suspend fun processRequest(context: HttpContext): Any? {
        try {
            // 1. 路由匹配
            val routeMatch = routeMatcher.match(
                path = context.request.path,
                method = context.request.method,
                routes = routes
            ) ?: throw RequestProcessingException.RouteNotFoundException(
                context.request.path,
                context.request.method
            )
            
            val argsMap = bindParameters(routeMatch, context)
            val args = neton.core.http.MapBackedHandlerArgs(argsMap)
            val result = routeMatch.route.handler.invoke(context, args)
            
            // 4. 响应处理
            handleResponse(result, context)
            
            return result
            
        } catch (e: RequestProcessingException) {
            handleError(e, context)
            return null // 发生错误时返回 null
        } catch (e: Exception) {
            handleError(
                RequestProcessingException.MethodInvocationException("unknown", e),
                context
            )
            return null // 发生错误时返回 null
        }
    }
    
    override fun registerRoute(route: RouteDefinition) {
        // 验证路由模式
        if (!PathPatternUtils.isValidPattern(route.pattern)) {
            throw IllegalArgumentException("Invalid route pattern: ${route.pattern}")
        }
        
        // 已存在相同 method+pattern 时跳过（避免 KSP 与 configure 重复调用导致冲突）
        val existingRoute = routes.find { 
            it.pattern == route.pattern && it.method == route.method 
        }
        if (existingRoute != null) {
            return // 已注册，静默跳过
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
    
    /**
     * 获取参数绑定器（供外部配置使用）
     */
    fun getParameterBinder(): ParameterBinder = parameterBinder
    
    /**
     * 绑定方法参数
     */
    private suspend fun bindParameters(
        routeMatch: RouteMatch,
        context: HttpContext
    ): Map<String, Any?> {
        val args = mutableMapOf<String, Any?>()
        
        for (binding in routeMatch.route.parameterBindings) {
            val value = parameterBinder.bindParameter(
                binding = binding,
                context = context,
                pathParameters = routeMatch.pathParameters
            )
            args[binding.parameterName] = value
        }
        
        return args
    }
    
    /**
     * 处理方法返回值
     */
    private suspend fun handleResponse(result: Any?, context: HttpContext) {
        when (result) {
            null -> {
                // 空返回值，不做处理
                context.response.text("")
            }
            is String -> {
                // 直接返回字符串，不需要JSON序列化
                context.response.contentType = "text/plain"
                context.response.text(result)
            }
            is ByteArray -> {
                context.response.write(result)
            }
            else -> {
                // 其他类型，尝试序列化为 JSON
                try {
                    val jsonString = serializeToJson(result)
                    context.response.json(jsonString)
                } catch (e: Exception) {
                    throw RequestProcessingException.ResponseSerializationException(e)
                }
            }
        }
    }
    
    /**
     * 处理错误
     */
    private suspend fun handleError(error: RequestProcessingException, context: HttpContext) {
        when (error) {
            is RequestProcessingException.RouteNotFoundException -> {
                context.response.status = HttpStatus.NOT_FOUND
                context.response.text("Route not found: ${error.message}")
            }
            is RequestProcessingException.ParameterBindingException -> {
                context.response.status = HttpStatus.BAD_REQUEST
                context.response.text("Parameter binding error: ${error.message}")
            }
            is RequestProcessingException.MethodInvocationException -> {
                context.response.status = HttpStatus.INTERNAL_SERVER_ERROR
                context.response.text("Internal server error: ${error.message}")
            }
            is RequestProcessingException.ResponseSerializationException -> {
                context.response.status = HttpStatus.INTERNAL_SERVER_ERROR
                context.response.text("Response serialization error: ${error.message}")
            }
        }
        
        logger?.error(
            "routing.request.error",
            mapOf("message" to (error.message ?: "")),
            cause = error
        )
    }
    
    /**
     * 序列化为 JSON
     * RESERVED FOR v1.1: 完整的 JSON 序列化
     */
    private fun serializeToJson(obj: Any): String {
        // 暂时简单处理
        return when (obj) {
            is Map<*, *> -> mapToJson(obj)
            is List<*> -> listToJson(obj)
            else -> obj.toString()
        }
    }
    
    /**
     * 简单的 Map 转 JSON
     */
    private fun mapToJson(map: Map<*, *>): String {
        val entries = map.entries.joinToString(",") { (k, v) ->
            "\"$k\":${valueToJson(v)}"
        }
        return "{$entries}"
    }
    
    /**
     * 简单的 List 转 JSON
     */
    private fun listToJson(list: List<*>): String {
        val items = list.joinToString(",") { valueToJson(it) }
        return "[$items]"
    }
    
    /**
     * 值转 JSON
     */
    private fun valueToJson(value: Any?): String {
        return when (value) {
            null -> "null"
            is String -> "\"${value.replace("\"", "\\\"")}\""
            is Number -> value.toString()
            is Boolean -> value.toString()
            is Map<*, *> -> mapToJson(value)
            is List<*> -> listToJson(value)
            else -> "\"${value.toString().replace("\"", "\\\"")}\""
        }
    }
}

/**
 * 请求引擎构建器
 */
class RequestEngineBuilder {
    private var routeMatcher: RouteMatcher = DefaultRouteMatcher()
    private var parameterBinder: ParameterBinder = DefaultParameterBinder()
    
    fun withRouteMatcher(matcher: RouteMatcher): RequestEngineBuilder {
        this.routeMatcher = matcher
        return this
    }
    
    fun withParameterBinder(binder: ParameterBinder): RequestEngineBuilder {
        this.parameterBinder = binder
        return this
    }
    
    fun build(): DefaultRequestEngine {
        return DefaultRequestEngine(routeMatcher, parameterBinder)
    }
} 