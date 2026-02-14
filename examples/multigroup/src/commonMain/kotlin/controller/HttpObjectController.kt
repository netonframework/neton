package controller

import neton.core.annotations.*
import neton.core.http.HttpRequest
import neton.core.http.HttpResponse
import neton.core.http.HttpSession
import neton.core.interfaces.Principal

/**
 * HTTP 对象控制器 - 展示 HTTP 对象的直接注入使用
 * 
 * 本控制器专注展示：
 * - HttpRequest 对象注入和使用
 * - HttpResponse 对象注入和使用
 * - HttpSession 对象注入和使用
 * - HTTP 对象与其他参数的组合使用
 * - 现代化的参数注入模式
 * 
 * 基础路径：/api/http
 */
@Controller("/api/http")
class HttpObjectController {
    
    /**
     * 获取当前时间戳的辅助函数
     */
    private fun getCurrentTimeMillis(): Long {
        return 1750329600000L // 使用固定时间戳进行演示
    }
    
    /**
     * HttpRequest 对象使用示例
     * 展示如何直接注入和使用 HttpRequest 对象
     */
    @Get("/request-info")
    fun requestInfo(request: HttpRequest): String {
        return """
            📨 HTTP Request 信息:
            - 方法: ${request.method}
            - 路径: ${request.path}
            - URL: ${request.url}
            - User-Agent: ${request.userAgent ?: "未知"}
            - 内容类型: ${request.contentType ?: "无"}
            - 远程地址: ${request.remoteAddress}
            - 是否安全连接: ${request.isSecure}
            - 是否Ajax请求: ${request.isAjax()}
            - 接受JSON: ${request.acceptsJson()}
            - 接受HTML: ${request.acceptsHtml()}
        """.trimIndent()
    }
    
    /**
     * HttpResponse 对象使用示例
     * 展示如何直接操作响应对象
     */
    @Get("/response-demo")
    suspend fun responseDemo(response: HttpResponse): String {
        // 设置自定义响应头
        response.header("X-API-Version", "1.0")
        response.header("X-Response-Time", getCurrentTimeMillis().toString())
        
        // 设置Cookie
        response.cookie("demo-cookie", "demo-value", maxAge = 3600, httpOnly = true)
        
        // 设置内容类型（虽然会被框架覆盖，但展示用法）
        response.contentType = "application/json; charset=utf-8"
        
        return "✨ HttpResponse 演示 - 检查响应头和Cookie"
    }
    
    /**
     * HttpSession 对象使用示例
     * 展示会话管理功能
     */
    @Get("/session-info")
    fun sessionInfo(session: HttpSession): String {
        // 获取或设置访问计数
        val visitCount = (session.getAttribute("visitCount") as? Int) ?: 0
        session.setAttribute("visitCount", visitCount + 1)
        
        // 设置其他会话属性
        session.setAttribute("lastAccess", getCurrentTimeMillis())
        session.setAttribute("userPreference", "dark-theme")
        
        return """
            🔗 HTTP Session 信息:
            - 会话ID: ${session.id}
            - 创建时间: ${session.creationTime}
            - 最后访问: ${session.lastAccessTime}
            - 是否新会话: ${session.isNew}
            - 是否有效: ${session.isValid}
            - 最大非活跃时间: ${session.maxInactiveInterval}秒
            - 剩余时间: ${session.getRemainingTime()}秒
            - 访问次数: ${visitCount + 1}
            - 会话大小: ${session.size()}个属性
            - 是否为空: ${session.isEmpty()}
        """.trimIndent()
    }
    
    /**
     * 组合使用示例 - 混合注入多种对象
     * 展示现代化的参数注入模式
     */
    @Post("/comprehensive")
    suspend fun comprehensiveExample(
        @QueryParam("action") action: String?,
        @QueryParam("format") format: String = "json",
        @Header("User-Agent") userAgent: String?,
        request: HttpRequest,
        response: HttpResponse,
        session: HttpSession,
        @AuthenticationPrincipal principal: Principal?
    ): String {
        // 记录请求信息到会话
        session.setAttribute("lastAction", action ?: "unknown")
        session.setAttribute("lastFormat", format)
        session.setAttribute("lastUserAgent", userAgent)
        
        // 根据用户身份设置不同的响应头
        if (principal != null) {
            response.header("X-User-ID", principal.id)
            response.header("X-User-Roles", principal.roles.joinToString(","))
        } else {
            response.header("X-User-Status", "anonymous")
        }
        
        // 设置内容类型
        when (format.lowercase()) {
            "xml" -> response.contentType = "application/xml"
            "plain" -> response.contentType = "text/plain"
            else -> response.contentType = "application/json"
        }
        
        // 记录响应时间
        response.header("X-Process-Time", "1ms") // 模拟处理时间
        
        val userInfo = if (principal != null) {
            "认证用户: ${principal.id} (${principal.roles.joinToString(", ")})"
        } else {
            "匿名用户"
        }
        
        return """
            🚀 综合HTTP对象使用演示:
            - 请求路径: ${request.path}
            - 请求方法: ${request.method}
            - 操作: ${action ?: "未指定"}
            - 格式: $format
            - 用户: $userInfo
            - 会话ID: ${session.id}
            - 远程地址: ${request.remoteAddress}
            - 用户代理: ${userAgent ?: "未知"}
        """.trimIndent()
    }
    
    /**
     * 文件上传处理示例
     * 展示请求体处理和响应操作
     */
    @Post("/upload")
    suspend fun uploadFile(
        request: HttpRequest,
        response: HttpResponse,
        session: HttpSession,
        @AuthenticationPrincipal principal: Principal?
    ) {
        try {
            // 检查内容类型
            val contentType = request.contentType
            if (contentType?.contains("multipart/form-data") != true && 
                contentType?.contains("application/octet-stream") != true) {
                response.badRequest("不支持的内容类型: $contentType")
                return
            }
            
            // 读取请求体
            val body = request.body()
            val bodySize = body.size
            
            // 记录上传信息到会话
            session.setAttribute("lastUploadSize", bodySize)
            session.setAttribute("lastUploadTime", getCurrentTimeMillis())
            
            // 设置响应头
            response.header("X-Upload-Size", bodySize.toString())
            response.header("X-Upload-User", principal?.id ?: "anonymous")
            
            // 返回成功响应
            response.json(mapOf(
                "success" to true,
                "message" to "文件上传成功",
                "size" to bodySize,
                "uploader" to (principal?.id ?: "anonymous"),
                "sessionId" to session.id,
                "timestamp" to getCurrentTimeMillis()
            ))
            
        } catch (e: Exception) {
            // 错误处理
            response.internalServerError("上传失败: ${e.message}")
        }
    }
    
    /**
     * 重定向示例
     * 展示响应重定向功能
     */
    @Get("/redirect-demo")
    suspend fun redirectDemo(
        @QueryParam("target") target: String?,
        response: HttpResponse
    ) {
        when (target) {
            "info" -> response.redirect("/api/http/request-info")
            "session" -> response.redirect("/api/http/session-info")
            "home" -> response.redirectPermanent("/")
            else -> response.redirect("/api/http/request-info")
        }
    }
    
    /**
     * 错误响应示例
     * 展示不同类型的错误响应
     */
    @Get("/error-demo/{type}")
    suspend fun errorDemo(
        @PathVariable("type") errorType: String,
        response: HttpResponse
    ) {
        when (errorType) {
            "400" -> response.badRequest("这是一个400错误示例")
            "401" -> response.unauthorized("需要身份验证")
            "403" -> response.forbidden("权限不足")
            "404" -> response.notFound("资源未找到")
            "500" -> response.internalServerError("服务器内部错误")
            else -> response.badRequest("未知错误类型: $errorType")
        }
    }
    
    /**
     * Cookie 管理示例
     * 展示Cookie的设置和删除
     */
    @Post("/cookie-demo")
    suspend fun cookieDemo(
        @FormParam("action") action: String,
        @FormParam("name") name: String?,
        @FormParam("value") value: String?,
        request: HttpRequest,
        response: HttpResponse
    ): String {
        when (action) {
            "set" -> {
                if (name != null && value != null) {
                    response.cookie(name, value, maxAge = 3600, httpOnly = true, secure = request.isSecure)
                    return "✅ Cookie已设置: $name = $value"
                } else {
                    response.badRequest("缺少name或value参数")
                    return ""
                }
            }
            "delete" -> {
                if (name != null) {
                    response.cookie(name, "", maxAge = 0) // 删除Cookie的标准方法
                    return "🗑️ Cookie已删除: $name"
                } else {
                    response.badRequest("缺少name参数")
                    return ""
                }
            }
            "list" -> {
                val cookies = request.cookies.entries.joinToString(", ") { "${it.key}=${it.value.value}" }
                return "🍪 当前Cookies: $cookies"
            }
            else -> {
                response.badRequest("不支持的操作: $action")
                return ""
            }
        }
    }
} 