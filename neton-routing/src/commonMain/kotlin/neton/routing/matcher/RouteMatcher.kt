package neton.routing.matcher

import neton.core.http.HttpMethod
import neton.routing.engine.RouteDefinition
import neton.routing.engine.RouteMatch

/**
 * 路由匹配器
 */
interface RouteMatcher {
    
    /**
     * 匹配路由
     */
    fun match(path: String, method: HttpMethod, routes: List<RouteDefinition>): RouteMatch?
}

/**
 * 默认路由匹配器实现
 * 
 * 支持路径参数占位符 {name} 的匹配
 */
class DefaultRouteMatcher : RouteMatcher {
    
    override fun match(path: String, method: HttpMethod, routes: List<RouteDefinition>): RouteMatch? {
        // 首先按HTTP方法过滤
        val methodRoutes = routes.filter { it.method == method }
        
        // 然后按路径匹配，优先匹配精确路径
        val exactMatch = methodRoutes.find { it.pattern == path }
        if (exactMatch != null) {
            return RouteMatch(exactMatch, emptyMap())
        }
        
        // 匹配带参数的路径
        for (route in methodRoutes) {
            val pathParams = matchPathPattern(route.pattern, path)
            if (pathParams != null) {
                return RouteMatch(route, pathParams)
            }
        }
        
        return null
    }
    
    /**
     * 匹配路径模式
     * 
     * @param pattern 路径模式，如 "/user/{id}/profile/{status}"
     * @param path 实际请求路径，如 "/user/123/profile/active"
     * @return 提取的路径参数，如果不匹配则返回 null
     */
    private fun matchPathPattern(pattern: String, path: String): Map<String, String>? {
        val patternSegments = pattern.split("/").filter { it.isNotEmpty() }
        val pathSegments = path.split("/").filter { it.isNotEmpty() }
        
        // 段数不匹配
        if (patternSegments.size != pathSegments.size) {
            return null
        }
        
        val pathParams = mutableMapOf<String, String>()
        
        for (i in patternSegments.indices) {
            val patternSegment = patternSegments[i]
            val pathSegment = pathSegments[i]
            
            when {
                // 参数占位符 {name}
                patternSegment.startsWith("{") && patternSegment.endsWith("}") -> {
                    val paramName = patternSegment.substring(1, patternSegment.length - 1)
                    pathParams[paramName] = pathSegment
                }
                // 精确匹配
                patternSegment == pathSegment -> {
                    // 继续匹配下一段
                }
                // 不匹配
                else -> {
                    return null
                }
            }
        }
        
        return pathParams
    }
}

/**
 * 路径模式工具类
 */
object PathPatternUtils {
    
    /**
     * 验证路径模式是否有效
     */
    fun isValidPattern(pattern: String): Boolean {
        // RESERVED FOR v1.1: 更完善的验证
        return true
    }
    
    /**
     * 从路径模式中提取参数名列表
     */
    fun extractParameterNames(pattern: String): List<String> {
        val regex = Regex("\\{([a-zA-Z_][a-zA-Z0-9_]*)\\}")
        return regex.findAll(pattern).map { it.groupValues[1] }.toList()
    }
    
    /**
     * 计算路由优先级
     * 精确匹配 > 参数较少 > 参数较多
     */
    fun calculatePriority(pattern: String): Int {
        val segments = pattern.split("/").filter { it.isNotEmpty() }
        var priority = segments.size * 1000  // 基础优先级
        
        // 减去参数数量（参数越少优先级越高）
        val paramCount = extractParameterNames(pattern).size
        priority -= paramCount * 100
        
        return priority
    }
} 