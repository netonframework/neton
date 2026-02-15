package neton.security

import neton.core.interfaces.Guard
import neton.core.interfaces.Identity
import neton.core.interfaces.RequestContext

/**
 * 默认守卫 - 允许所有已认证用户访问
 */
class DefaultGuard : Guard {
    override val name = "default"

    override suspend fun checkPermission(identity: Identity?, context: RequestContext): Boolean {
        return identity != null
    }
}

/**
 * 公开守卫 - 允许所有用户访问，包括未认证用户
 */
class PublicGuard : Guard {
    override val name = "public"

    override suspend fun checkPermission(identity: Identity?, context: RequestContext): Boolean {
        return true
    }
}

/**
 * 管理员守卫 - 只允许具有 admin 角色的用户访问
 */
class AdminGuard : Guard {
    override val name = "admin"

    override suspend fun checkPermission(identity: Identity?, context: RequestContext): Boolean {
        return identity?.hasRole("admin") ?: false
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

    override suspend fun checkPermission(identity: Identity?, context: RequestContext): Boolean {
        if (identity == null) return false

        return if (requireAll) {
            identity.hasAllRoles(*requiredRoles.toTypedArray())
        } else {
            identity.hasAnyRole(*requiredRoles.toTypedArray())
        }
    }
}

/**
 * 自定义守卫 - 允许自定义权限检查逻辑
 */
class CustomGuard(
    override val name: String,
    private val authorizer: suspend (identity: Identity?, context: RequestContext) -> Boolean
) : Guard {
    override suspend fun checkPermission(identity: Identity?, context: RequestContext): Boolean {
        return authorizer(identity, context)
    }
}
