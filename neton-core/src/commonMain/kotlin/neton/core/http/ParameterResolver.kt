package neton.core.http

import neton.core.annotations.*
import neton.core.interfaces.Principal
import neton.core.interfaces.RequestEngine

/**
 * 参数解析器接口
 * 负责解析控制器方法的参数
 */
interface ParameterResolver {
    /**
     * 检查是否可以解析指定参数
     */
    fun canResolve(parameterType: String, annotations: List<String>): Boolean
    
    /**
     * 解析参数值
     */
    suspend fun resolve(parameterName: String?, context: HttpContext): Any?
    
    /**
     * 解析器优先级（数值越小优先级越高）
     */
    val priority: Int get() = 100
}

/**
 * 认证用户参数解析器
 * 处理 @AuthenticationPrincipal 注解的参数
 */
class AuthenticationPrincipalResolver : ParameterResolver {
    
    override val priority: Int = 10
    
    override fun canResolve(parameterType: String, annotations: List<String>): Boolean {
        return annotations.contains("AuthenticationPrincipal")
    }
    
    override suspend fun resolve(parameterName: String?, context: HttpContext): Any? {
        // 从安全上下文获取当前用户
        return context.getAttribute("principal") as? Principal
            ?: throw IllegalStateException("Authentication required but no user found")
    }
}

/**
 * HTTP请求参数解析器
 * 处理 HttpRequest 类型的参数
 */
class HttpRequestResolver : ParameterResolver {
    
    override val priority: Int = 20
    
    override fun canResolve(parameterType: String, annotations: List<String>): Boolean {
        return parameterType == "HttpRequest"
    }
    
    override suspend fun resolve(parameterName: String?, context: HttpContext): Any? {
        return context.request
    }
}

/**
 * HTTP响应参数解析器
 * 处理 HttpResponse 类型的参数
 */
class HttpResponseResolver : ParameterResolver {
    
    override val priority: Int = 20
    
    override fun canResolve(parameterType: String, annotations: List<String>): Boolean {
        return parameterType == "HttpResponse"
    }
    
    override suspend fun resolve(parameterName: String?, context: HttpContext): Any? {
        return context.response
    }
}

/**
 * HTTP会话参数解析器
 * 处理 HttpSession 类型的参数
 */
class HttpSessionResolver : ParameterResolver {
    
    override val priority: Int = 20
    
    override fun canResolve(parameterType: String, annotations: List<String>): Boolean {
        return parameterType == "HttpSession"
    }
    
    override suspend fun resolve(parameterName: String?, context: HttpContext): Any? {
        return context.session
    }
}

/**
 * 路径参数解析器
 * 处理 @PathVariable 注解的参数
 */
class PathVariableResolver : ParameterResolver {
    
    override val priority: Int = 30
    
    override fun canResolve(parameterType: String, annotations: List<String>): Boolean {
        return annotations.contains("PathVariable")
    }
    
    override suspend fun resolve(parameterName: String?, context: HttpContext): Any? {
        val paramName = parameterName ?: return null
        return context.request.pathParam(paramName)
    }
}

/**
 * 查询参数解析器
 * 处理 @QueryParam 注解的参数
 */
class QueryParamResolver : ParameterResolver {
    
    override val priority: Int = 30
    
    override fun canResolve(parameterType: String, annotations: List<String>): Boolean {
        return annotations.contains("QueryParam")
    }
    
    override suspend fun resolve(parameterName: String?, context: HttpContext): Any? {
        val paramName = parameterName ?: return null
        return context.request.queryParam(paramName)
    }
}

/**
 * 表单参数解析器
 * 处理 @FormParam 注解的参数
 */
class FormParamResolver : ParameterResolver {
    
    override val priority: Int = 30
    
    override fun canResolve(parameterType: String, annotations: List<String>): Boolean {
        return annotations.contains("FormParam")
    }
    
    override suspend fun resolve(parameterName: String?, context: HttpContext): Any? {
        val paramName = parameterName ?: return null
        val formData = context.request.form()
        return formData[paramName]
    }
}

/**
 * 请求头参数解析器
 * 处理 @Header 注解的参数
 */
class HeaderResolver : ParameterResolver {
    
    override val priority: Int = 30
    
    override fun canResolve(parameterType: String, annotations: List<String>): Boolean {
        return annotations.contains("Header")
    }
    
    override suspend fun resolve(parameterName: String?, context: HttpContext): Any? {
        val headerName = parameterName ?: return null
        return context.request.header(headerName)
    }
}

/**
 * Cookie参数解析器
 * 处理 @Cookie 注解的参数
 */
class CookieResolver : ParameterResolver {
    
    override val priority: Int = 30
    
    override fun canResolve(parameterType: String, annotations: List<String>): Boolean {
        return annotations.contains("Cookie")
    }
    
    override suspend fun resolve(parameterName: String?, context: HttpContext): Any? {
        val cookieName = parameterName ?: return null
        val cookie = context.request.cookie(cookieName) ?: return null
        return cookie.value
    }
}

/**
 * 请求体参数解析器
 * 处理 @Body 注解的参数
 */
class BodyResolver : ParameterResolver {
    
    override val priority: Int = 30
    
    override fun canResolve(parameterType: String, annotations: List<String>): Boolean {
        return annotations.contains("Body")
    }
    
    override suspend fun resolve(parameterName: String?, context: HttpContext): Any? {
        val contentType = context.request.contentType
        
        return when {
            contentType?.contains("application/json") == true -> {
                context.request.json()
            }
            contentType?.contains("application/x-www-form-urlencoded") == true -> {
                context.request.form()
            }
            contentType?.contains("text/") == true -> {
                context.request.text()
            }
            else -> {
                context.request.body()
            }
        }
    }
}

/**
 * 参数解析器注册表
 */
class ParameterResolverRegistry {
    
    private val resolvers = mutableListOf<ParameterResolver>()
    
    init {
        // 注册默认解析器
        register(AuthenticationPrincipalResolver())
        register(HttpRequestResolver())
        register(HttpResponseResolver())
        register(HttpSessionResolver())
        register(PathVariableResolver())
        register(QueryParamResolver())
        register(FormParamResolver())
        register(HeaderResolver())
        register(CookieResolver())
        register(BodyResolver())
    }
    
    /**
     * 注册参数解析器
     */
    fun register(resolver: ParameterResolver) {
        resolvers.add(resolver)
        // 按优先级排序
        resolvers.sortBy { it.priority }
    }
    
    /**
     * 解析方法参数
     */
    suspend fun resolveParameters(
        parameterInfos: List<ParameterInfo>,
        context: HttpContext
    ): Array<Any?> {
        return parameterInfos.map { paramInfo ->
            val resolver = resolvers.find { it.canResolve(paramInfo.type, paramInfo.annotations) }
            resolver?.resolve(paramInfo.name, context)
        }.toTypedArray()
    }
}

/**
 * 参数信息
 */
data class ParameterInfo(
    val name: String?,
    val type: String,
    val annotations: List<String>
) 