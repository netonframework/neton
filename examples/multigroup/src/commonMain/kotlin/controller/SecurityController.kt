package controller

import kotlinx.serialization.Serializable
import neton.core.annotations.*
import neton.core.interfaces.Principal

/**
 * 安全控制器 - 展示各种安全注解的使用
 * 
 * 本控制器专注展示：
 * - 身份认证注解
 * - 授权注解
 * - 角色权限控制
 * - 匿名访问控制
 * - 主体注入
 * 
 * 基础路径：/api/security
 */
@Controller("/api/security")
class SecurityController {
    
    /**
     * 公开访问 - @AllowAnonymous
     * 任何人都可以访问，无需认证
     */
    @Get("/public")
    @AllowAnonymous
    fun publicAccess(): String {
        return "🌍 公开接口 - 任何人都可以访问"
    }
    
    /**
     * 需要认证 - @RequireAuth
     * 必须先登录才能访问
     */
    @Get("/protected")
    @RequireAuth
    fun protectedAccess(): String {
        return "🔒 受保护接口 - 需要登录才能访问"
    }
    
    /**
     * 管理员权限 - @RolesAllowed
     * 只有管理员角色才能访问
     */
    @Get("/admin")
    @RolesAllowed("admin")
    fun adminOnly(): String {
        return "👑 管理员接口 - 只有管理员才能访问"
    }
    
    /**
     * 多角色权限 - @RolesAllowed
     * 管理员或编辑者都可以访问
     */
    @Get("/editor")
    @RolesAllowed("admin", "editor")
    fun adminOrEditor(): String {
        return "✏️ 编辑权限接口 - 管理员或编辑者可以访问"
    }
    
    /**
     * 用户权限 - @RolesAllowed
     * 普通用户权限即可访问
     */
    @Get("/user")
    @RolesAllowed("user", "admin", "editor")
    fun userAccess(): String {
        return "👤 用户接口 - 普通用户权限即可访问"
    }
    
    /**
     * 获取当前用户信息 - @AuthenticationPrincipal
     * 注入当前认证的用户主体
     */
    @Get("/profile")
    @RequireAuth
    fun getCurrentUser(@AuthenticationPrincipal principal: Principal): String {
        return "👥 当前用户: ${principal.id} (角色: ${principal.roles.joinToString(", ")})"
    }
    
    /**
     * 修改用户资料 - 需要认证 + 主体注入
     * 用户只能修改自己的资料
     */
    @Put("/profile")
    @RequireAuth
    fun updateProfile(
        @AuthenticationPrincipal principal: Principal,
        @FormParam("displayName") displayName: String
    ): String {
        return "📝 更新用户资料 - ${principal.id} 更新显示名称为: '$displayName'"
    }
    
    /**
     * 用户管理 - 超级管理员权限
     * 只有超级管理员才能进行用户管理
     */
    @Post("/manage/users")
    @RolesAllowed("super-admin")
    fun manageUsers(@FormParam("action") action: String): String {
        return "🛠️ 用户管理 - 执行操作: $action (需要超级管理员权限)"
    }
    
    /**
     * 系统设置 - 管理员权限
     * 管理员可以修改系统设置
     */
    @Put("/settings")
    @RolesAllowed("admin")
    fun updateSettings(
        @AuthenticationPrincipal principal: Principal,
        @Body settings: SystemSettings
    ): String {
        return "⚙️ 系统设置 - ${principal.id} 更新设置: ${settings.key} = ${settings.value}"
    }
    
    /**
     * 审计日志 - 审计员或管理员权限
     * 只有审计员或管理员可以查看审计日志
     */
    @Get("/audit")
    @RolesAllowed("admin", "auditor")
    fun getAuditLog(
        @QueryParam("from") from: String?,
        @QueryParam("to") to: String?
    ): String {
        return "📊 审计日志 - 查询范围: ${from ?: "开始"} 到 ${to ?: "现在"}"
    }
    
    /**
     * 访客信息 - 匿名或认证用户都可访问
     * 展示如何处理可选的用户主体
     */
    @Get("/visitor")
    @AllowAnonymous
    fun visitorInfo(@AuthenticationPrincipal principal: Principal?): String {
        return if (principal != null) {
            "👋 欢迎回来, ${principal.id}! (已登录用户)"
        } else {
            "👋 欢迎访客! (匿名用户)"
        }
    }
    
    /**
     * 复杂权限场景 - 组合多种安全注解
     * 需要认证，且根据角色返回不同内容
     */
    @Get("/dashboard")
    @RequireAuth
    fun dashboard(@AuthenticationPrincipal principal: Principal): String {
        val content = when {
            "admin" in principal.roles -> "🎛️ 管理员仪表板 - 完整系统控制权限"
            "editor" in principal.roles -> "📝 编辑器仪表板 - 内容管理权限"
            "user" in principal.roles -> "📱 用户仪表板 - 个人账户管理"
            else -> "❓ 基础仪表板 - 有限功能"
        }
        return "$content (用户: ${principal.id})"
    }
}

/**
 * 系统设置数据类
 */
@Serializable
data class SystemSettings(
    val key: String,
    val value: String,
    val description: String? = null
) 