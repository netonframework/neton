package controller

import neton.core.annotations.*
import kotlin.time.TimeSource

/**
 * 路径模式控制器 - 展示各种路径模式和响应类型
 * 
 * 本控制器专注展示：
 * - 复杂路径模式
 * - 路径参数匹配
 * - 通配符路径
 * - 不同响应类型
 * - 内容协商
 * 
 * 基础路径：/api/patterns
 */
@Controller("/api/patterns")
class PathPatternController {
    
    /**
     * 简单路径
     * 示例：GET /api/patterns/simple
     */
    @Get("/simple")
    fun simplePath(): String {
        return "📍 简单路径匹配"
    }
    
    /**
     * 单个路径参数
     * 示例：GET /api/patterns/users/123
     */
    @Get("/users/{id}")
    fun singlePathVariable(@PathVariable("id") id: Int): String {
        return "👤 单个路径参数 - ID: $id"
    }
    
    /**
     * 多个路径参数
     * 示例：GET /api/patterns/users/123/posts/456
     */
    @Get("/users/{userId}/posts/{postId}")
    fun multiplePathVariables(
        @PathVariable("userId") userId: Int,
        @PathVariable("postId") postId: Int
    ): String {
        return "📚 多个路径参数 - 用户ID: $userId, 文章ID: $postId"
    }
    
    /**
     * 嵌套资源路径
     * 示例：GET /api/patterns/organizations/123/departments/456/employees/789
     */
    @Get("/organizations/{orgId}/departments/{deptId}/employees/{empId}")
    fun nestedResource(
        @PathVariable("orgId") orgId: Int,
        @PathVariable("deptId") deptId: Int,
        @PathVariable("empId") empId: Int
    ): String {
        return "🏢 嵌套资源 - 组织:$orgId -> 部门:$deptId -> 员工:$empId"
    }
    
    /**
     * 可选路径段（通过查询参数模拟）
     * 示例：GET /api/patterns/files/document.pdf?version=2
     */
    @Get("/files/{filename}")
    fun fileAccess(
        @PathVariable("filename") filename: String,
        @QueryParam("version") version: Int?
    ): String {
        val versionInfo = version?.let { " (版本 $it)" } ?: ""
        return "📁 文件访问 - 文件名: $filename$versionInfo"
    }
    
    /**
     * 通配符路径（模拟）
     * 示例：GET /api/patterns/static/images/logo.png
     */
    @Get("/static/{path}")
    fun staticResource(@PathVariable("path") path: String): String {
        return "🖼️ 静态资源 - 路径: $path"
    }
    
    /**
     * 带扩展名的路径
     * 示例：GET /api/patterns/reports/sales.json
     */
    @Get("/reports/{name}.{format}")
    fun reportWithFormat(
        @PathVariable("name") name: String,
        @PathVariable("format") format: String
    ): String {
        return "📊 报告文件 - 名称: $name, 格式: $format"
    }
    
    /**
     * 正则表达式路径（模拟约束）
     * 示例：GET /api/patterns/products/ABC123
     */
    @Get("/products/{code}")
    fun productByCode(@PathVariable("code") code: String): String {
        // 模拟验证产品代码格式
        val isValid = code.matches(Regex("[A-Z]{3}\\d{3}"))
        return if (isValid) {
            "📦 有效产品代码: $code"
        } else {
            "❌ 无效产品代码格式: $code (应为: ABC123)"
        }
    }
    
    /**
     * 版本化API路径
     * 示例：GET /api/patterns/v1/data
     */
    @Get("/v1/data")
    fun apiV1(): String {
        return "📡 API v1.0 - 旧版本数据接口"
    }
    
    /**
     * 版本化API路径 v2
     * 示例：GET /api/patterns/v2/data
     */
    @Get("/v2/data")
    fun apiV2(): String {
        return "🚀 API v2.0 - 新版本数据接口 (增强功能)"
    }
    
    /**
     * JSON 响应
     * 示例：GET /api/patterns/json
     */
    @Get("/json")
    fun jsonResponse(): Map<String, Any> {
        return mapOf(
            "message" to "JSON 响应示例",
            "timestamp" to 1703123456789L, // 固定时间戳示例
            "data" to listOf("item1", "item2", "item3")
        )
    }
    
    /**
     * 纯文本响应
     * 示例：GET /api/patterns/text
     */
    @Get("/text")
    fun textResponse(): String {
        return "这是一个纯文本响应"
    }
    
    /**
     * 数字响应
     * 示例：GET /api/patterns/number
     */
    @Get("/number")
    fun numberResponse(): Int {
        return 42
    }
    
    /**
     * 布尔响应
     * 示例：GET /api/patterns/boolean
     */
    @Get("/boolean")
    fun booleanResponse(): Boolean {
        return true
    }
    
    /**
     * 空响应
     * 示例：DELETE /api/patterns/cleanup
     */
    @Delete("/cleanup")
    fun voidResponse(): Unit {
        // 执行清理操作，无返回值
    }
    
    /**
     * 复杂对象响应
     * 示例：GET /api/patterns/complex
     */
    @Get("/complex")
    fun complexResponse(): ApiResponse {
        return ApiResponse(
            success = true,
            message = "复杂对象响应示例",
            data = ResponseData(
                id = 12345,
                name = "示例数据",
                tags = listOf("kotlin", "framework", "web")
            ),
            metadata = mapOf(
                "version" to "1.0",
                "generated" to 1703123456789L // 固定时间戳示例
            )
        )
    }
    
    /**
     * 条件响应 - 根据参数返回不同格式
     * 示例：GET /api/patterns/conditional?format=xml
     */
    @Get("/conditional")
    fun conditionalResponse(@QueryParam("format") format: String?): Any {
        return when (format?.lowercase()) {
            "xml" -> "<?xml version=\"1.0\"?><response><message>XML 格式响应</message></response>"
            "plain" -> "纯文本格式响应"
            "number" -> 2023
            else -> mapOf("message" to "默认 JSON 格式响应", "format" to "json")
        }
    }
}

/**
 * API 响应包装类
 */
data class ApiResponse(
    val success: Boolean,
    val message: String,
    val data: ResponseData? = null,
    val metadata: Map<String, Any>? = null
)

/**
 * 响应数据类
 */
data class ResponseData(
    val id: Int,
    val name: String,
    val tags: List<String> = emptyList()
) 