package neton.routing.binder

import neton.routing.engine.*
import neton.core.http.HttpContext
import neton.core.security.AuthenticationContext
import kotlin.reflect.KClass

/**
 * 参数绑定器
 * 
 * 负责从 HTTP 上下文中提取和转换参数值
 */
interface ParameterBinder {
    
    /**
     * 绑定参数值
     */
    suspend fun bindParameter(
        binding: ParameterBinding,
        context: HttpContext,
        pathParameters: Map<String, String>
    ): Any?
    
    /**
     * 设置认证上下文
     */
    fun setAuthenticationContext(authContext: AuthenticationContext)
}

/**
 * 默认参数绑定器实现
 * 
 * 支持路径参数、请求体、认证主体和上下文对象的绑定
 */
class DefaultParameterBinder : ParameterBinder {
    
    private var authenticationContext: AuthenticationContext? = null
    
    override fun setAuthenticationContext(authContext: AuthenticationContext) {
        this.authenticationContext = authContext
    }
    
    override suspend fun bindParameter(
        binding: ParameterBinding,
        context: HttpContext,
        pathParameters: Map<String, String>
    ): Any? {
        return try {
            when (binding) {
                is ParameterBinding.PathVariable -> bindPathVariable(binding, pathParameters)
                is ParameterBinding.RequestBody -> bindRequestBody(binding, context)
                is ParameterBinding.AuthenticationPrincipal -> bindAuthenticationPrincipal(binding)
                is ParameterBinding.ContextObject -> bindContextObject(binding, context)
            }
        } catch (e: Exception) {
            throw RequestProcessingException.ParameterBindingException(binding.parameterName, e)
        }
    }
    
    /**
     * 绑定路径参数
     */
    private fun bindPathVariable(
        binding: ParameterBinding.PathVariable,
        pathParameters: Map<String, String>
    ): Any? {
        val value = pathParameters[binding.pathKey]
            ?: throw IllegalArgumentException("Path parameter '${binding.pathKey}' not found")
        
        return TypeConverter.convertFromString(value, binding.parameterType)
    }
    
    /**
     * 绑定请求体
     */
    private suspend fun bindRequestBody(
        binding: ParameterBinding.RequestBody,
        context: HttpContext
    ): Any? {
        val jsonText = context.request.text()
        if (jsonText.isEmpty()) {
            throw IllegalArgumentException("Request body is empty")
        }
        
        return when (binding.parameterType) {
            String::class -> jsonText
            else -> {
                // RESERVED FOR v1.1: JSON 反序列化
                // 暂时返回 JSON 字符串，KSP 生成的代码可以处理具体的反序列化
                throw UnsupportedOperationException("JSON deserialization not implemented yet for type: ${binding.parameterType}")
            }
        }
    }
    
    /**
     * 绑定认证主体
     */
    private fun bindAuthenticationPrincipal(
        binding: ParameterBinding.AuthenticationPrincipal
    ): Any? {
        val principal = authenticationContext?.currentUser()
        
        if (principal == null && binding.required) {
            throw IllegalArgumentException("Authentication required but no principal found")
        }
        
        return principal
    }
    
    /**
     * 绑定上下文对象
     */
    private fun bindContextObject(
        binding: ParameterBinding.ContextObject,
        context: HttpContext
    ): Any? {
        return when (binding.parameterType) {
            neton.core.http.HttpRequest::class -> context.request
            neton.core.http.HttpResponse::class -> context.response
            neton.core.http.HttpSession::class -> context.session
            HttpContext::class -> context
            else -> throw IllegalArgumentException("Unsupported context object type: ${binding.parameterType}")
        }
    }
}

/**
 * 类型转换工具
 */
object TypeConverter {
    
    /**
     * 支持的基础类型转换
     */
    fun convertFromString(value: String, targetType: KClass<*>): Any {
        return when (targetType) {
            String::class -> value
            Int::class -> value.toIntOrNull() ?: throw IllegalArgumentException("Cannot convert '$value' to Int")
            Long::class -> value.toLongOrNull() ?: throw IllegalArgumentException("Cannot convert '$value' to Long")
            Boolean::class -> value.toBooleanStrictOrNull() ?: throw IllegalArgumentException("Cannot convert '$value' to Boolean")
            Double::class -> value.toDoubleOrNull() ?: throw IllegalArgumentException("Cannot convert '$value' to Double")
            Float::class -> value.toFloatOrNull() ?: throw IllegalArgumentException("Cannot convert '$value' to Float")
            else -> throw IllegalArgumentException("Unsupported type conversion to $targetType")
        }
    }
    
    /**
     * 检查类型是否支持转换
     */
    fun isConvertibleType(type: KClass<*>): Boolean {
        return when (type) {
            String::class, Int::class, Long::class, Boolean::class, Double::class, Float::class -> true
            else -> false
        }
    }
} 