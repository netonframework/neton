package controller

import kotlinx.serialization.Serializable
import neton.core.annotations.*
import neton.core.http.Ctx

/**
 * 参数绑定控制器 - 规范 v1.0.1 约定风格
 * 
 * 约定优于配置：Path/Query/Body 多数场景零注解
 * 仅 Header/Cookie/Form 需显式注解
 */
@Controller("/api/binding")
class ParameterBindingController {
    
    /** 约定：param 名 = {userId} → Path */
    @Get("/users/{userId}")
    fun pathParam(userId: Int) =
        "👤 路径参数 userId: $userId"
    
    /** 约定：GET + 简单类型 → Query */
    @Get("/search")
    fun search(keyword: String, page: Int = 1, size: Int = 10) =
        "🔍 查询参数 - keyword: '$keyword', page: $page, size: $size"
    
    /**
     * 表单参数绑定 - @FormParam
     * 示例：POST /api/binding/form (Content-Type: application/x-www-form-urlencoded)
     */
    @Post("/form")
    fun formParam(
        @FormParam("username") username: String,
        @FormParam("email") email: String,
        @FormParam("age") age: Int?
    ): String {
        return "📝 表单参数 - username: '$username', email: '$email', age: $age"
    }
    
    /**
     * 请求头绑定 - @Header
     * 示例：GET /api/binding/headers (带自定义请求头)
     */
    @Get("/headers")
    fun headerParam(
        @Header("User-Agent") userAgent: String,
        @Header("Accept-Language") language: String = "en",
        @Header("X-Custom-Header") customHeader: String?
    ): String {
        return "📨 请求头参数 - User-Agent: '$userAgent', Language: '$language', Custom: '$customHeader'"
    }
    
    /** 约定：POST + 复杂类型 → Body */
    @Post("/json")
    fun create(req: BindingUserRequest) =
        "📄 请求体 - name: '${req.name}', email: '${req.email}', age: ${req.age}"
    
    /**
     * Cookie 绑定 - @Cookie
     * 示例：GET /api/binding/cookies (带 Cookie)
     */
    @Get("/cookies")
    fun cookieParam(
        @Cookie("sessionId") sessionId: String?,
        @Cookie("theme") theme: String = "light"
    ): String {
        return "🍪 Cookie 参数 - sessionId: '$sessionId', theme: '$theme'"
    }
    
    /** 约定：id + version + ctx；Header/Form 需显式 */
    @Put("/complex/{resourceId}")
    fun complex(
        resourceId: String,
        version: Int = 1,
        @Header("Authorization") auth: String?,
        @FormParam("action") action: String,
        ctx: Ctx
    ) = "🔗 resourceId: $resourceId, version: $version, auth: ${auth?.take(10)}..., ctx: ${ctx::class.simpleName}"
    
    /** List 多值：?tags=a&tags=b → tags: List<String> */
    @Get("/filters")
    fun filters(tags: List<String>, ids: List<Int>?) =
        "📋 tags: ${tags.joinToString(", ")}, ids: ${ids?.joinToString(", ") ?: "null"}"
    
    /** 可选参数 */
    @Get("/optional")
    fun optional(required: String, optional: String?, @Header("X-Optional") header: String? = null) =
        "❓ required: '$required', optional: '$optional', header: '${header ?: "default"}'"
}

/**
 * 用于 @Body 绑定的数据类
 */
@Serializable
data class BindingUserRequest(
    val name: String,
    val email: String,
    val age: Int? = null
) 