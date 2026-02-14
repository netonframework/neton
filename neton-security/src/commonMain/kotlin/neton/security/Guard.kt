package neton.security

/**
 * 权限守卫接口 - 专注于权限控制逻辑
 */
interface Guard {
    /**
     * 执行权限检查
     * @param principal 已认证的用户主体，可能为 null
     * @param context 请求上下文
     * @return true 如果允许访问，false 如果拒绝访问
     */
    suspend fun authorize(principal: Principal?, context: RequestContext): Boolean
    
    /**
     * 守卫名称
     */
    val name: String
}

/**
 * 默认守卫 - 允许所有已认证用户访问
 */
class DefaultGuard : Guard {
    override val name = "default"
    
    override suspend fun authorize(principal: Principal?, context: RequestContext): Boolean {
        return principal != null
    }
}

/**
 * 公开守卫 - 允许所有用户访问，包括未认证用户
 */
class PublicGuard : Guard {
    override val name = "public"
    
    override suspend fun authorize(principal: Principal?, context: RequestContext): Boolean {
        return true
    }
}

/**
 * 管理员守卫 - 只允许具有 admin 角色的用户访问
 */
class AdminGuard : Guard {
    override val name = "admin"
    
    override suspend fun authorize(principal: Principal?, context: RequestContext): Boolean {
        return principal?.hasRole("admin") ?: false
    }
}

/**
 * 角色守卫 - 只允许具有指定角色的用户访问
 */
class RoleGuard(
    private val requiredRoles: List<String>,
    private val requireAll: Boolean = false
) : Guard {
    override val name = "role"
    
    constructor(vararg roles: String, requireAll: Boolean = false) : this(roles.toList(), requireAll)
    
    override suspend fun authorize(principal: Principal?, context: RequestContext): Boolean {
        if (principal == null) return false
        
        return if (requireAll) {
            principal.hasAllRoles(*requiredRoles.toTypedArray())
        } else {
            principal.hasAnyRole(*requiredRoles.toTypedArray())
        }
    }
}

/**
 * 自定义守卫 - 允许自定义权限检查逻辑
 */
class CustomGuard(
    override val name: String,
    private val authorizer: suspend (principal: Principal?, context: RequestContext) -> Boolean
) : Guard {
    override suspend fun authorize(principal: Principal?, context: RequestContext): Boolean {
        return authorizer(principal, context)
    }
} 