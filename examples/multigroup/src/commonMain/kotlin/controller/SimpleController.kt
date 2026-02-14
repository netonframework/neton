package controller

import kotlinx.serialization.Serializable
import neton.core.annotations.*
import neton.core.http.*
import neton.core.interfaces.Principal

/**
 * 简单控制器 - 展示新注解系统的使用
 * 
 * 基础路径：/simple
 */
@Controller("/simple")
class SimpleController {
    
    /**
     * 简单的GET方法
     * 路由：GET /simple/hello
     */
    @Get("/hello")
    fun hello(): String {
        return "Hello from SimpleController!"
    }
    
    /**
     * 路径参数绑定 - @PathVariable
     * 示例：GET /simple/user/123
     */
    @Get("/user/{id}")
    fun getUser(@PathVariable("id") userId: Int): String {
        return "获取用户 ID: $userId"
    }
    
    /**
     * 多个路径参数
     * 示例：GET /simple/user/123/post/456
     */
    @Get("/user/{userId}/post/{postId}")
    fun getUserPost(
        @PathVariable("userId") userId: Int,
        @PathVariable("postId") postId: Int
    ): String {
        return "用户 $userId 的帖子 $postId"
    }
    
    /**
     * 请求体绑定 - @Body
     * 示例：POST /simple/user (Content-Type: application/json)
     */
    @Post("/user")
    fun createUser(@Body user: CreateUserRequest): String {
        return "创建用户: ${user.name}, ${user.email}"
    }
    
    /**
     * HTTP上下文对象注入
     * 自动注入HttpRequest对象
     */
    @Get("/request-info")
    fun getRequestInfo(request: HttpRequest): String {
        return "请求方法: ${request.method}, 路径: ${request.path}"
    }
    
    /**
     * 认证用户注入 - @AuthenticationPrincipal
     * 注入当前认证的用户
     */
    @Get("/profile")
    fun getProfile(@AuthenticationPrincipal user: Principal?): String {
        return if (user != null) {
            "当前用户: ${user.id}, 角色: ${user.roles}"
        } else {
            "未认证用户"
        }
    }
}

/**
 * 用于创建用户的请求数据类
 */
@Serializable
data class CreateUserRequest(
    val name: String,
    val email: String,
    val age: Int? = null
) 