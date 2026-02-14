package neton.routing

import neton.core.http.HttpMethod
import neton.routing.annotations.*

/**
 * 简化的注解处理器
 * 用于演示带路径参数的注解功能
 */
object AnnotationProcessor {
    
    /**
     * 路由信息数据类
     */
    data class RouteInfo(
        val method: String,
        val path: String,
        val methodName: String,
        val controllerPath: String = ""
    )
    
    /**
     * 组合控制器基础路径和方法路径
     */
    fun combinePaths(basePath: String, methodPath: String): String {
        return when {
            basePath.isEmpty() && methodPath.isEmpty() -> "/"
            basePath.isEmpty() -> if (methodPath.startsWith("/")) methodPath else "/$methodPath"
            methodPath.isEmpty() || methodPath == "/" -> basePath
            methodPath.startsWith("/") -> "$basePath$methodPath"
            else -> "$basePath/$methodPath"
        }
    }
    
    /**
     * 标准化路径
     */
    fun normalizePath(path: String): String {
        return when {
            path.isEmpty() -> ""
            path == "/" -> ""
            path.startsWith("/") && path.endsWith("/") -> path.dropLast(1)
            path.startsWith("/") -> path
            else -> "/$path"
        }
    }
    
    /**
     * 验证路径格式
     */
    fun validatePath(path: String): Boolean {
        if (path.isEmpty()) return true
        if (!path.startsWith("/")) return false
        if (path.contains("//")) return false
        return true
        }
        
    /**
     * 创建路由信息
     */
    fun createRouteInfo(
        method: String,
        controllerPath: String,
        methodPath: String,
        methodName: String
    ): RouteInfo {
        val normalizedControllerPath = normalizePath(controllerPath)
        val fullPath = combinePaths(normalizedControllerPath, methodPath)
        
            return RouteInfo(
            method = method,
            path = fullPath,
            methodName = methodName,
            controllerPath = normalizedControllerPath
        )
    }
} 