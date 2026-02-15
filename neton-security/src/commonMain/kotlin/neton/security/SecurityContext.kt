package neton.security

import neton.core.interfaces.Identity
import neton.core.security.AuthenticationContext

/**
 * 安全上下文 - 提供全局用户访问
 */
object SecurityContext : AuthenticationContext {
    private var currentIdentity: Identity? = null

    /**
     * 设置当前用户身份
     * @param identity 用户身份，null 表示未认证
     */
    fun setIdentity(identity: Identity?) {
        currentIdentity = identity
    }

    /**
     * 实现 AuthenticationContext 接口
     */
    override fun currentUser(): Any? = currentIdentity

    /**
     * 获取当前用户身份
     * @return 当前用户身份，未认证时返回 null
     */
    fun currentIdentity(): Identity? = currentIdentity

    /**
     * 检查用户是否已认证
     */
    fun isAuthenticated(): Boolean = currentIdentity != null

    /**
     * 检查用户是否具有指定角色
     */
    fun hasRole(role: String): Boolean =
        currentIdentity?.hasRole(role) ?: false

    /**
     * 检查用户是否具有任意一个指定角色
     */
    fun hasAnyRole(vararg roles: String): Boolean =
        currentIdentity?.hasAnyRole(*roles) ?: false

    /**
     * 检查用户是否具有所有指定角色
     */
    fun hasAllRoles(vararg roles: String): Boolean =
        currentIdentity?.hasAllRoles(*roles) ?: false

    /**
     * 检查用户是否具有指定权限
     */
    fun hasPermission(permission: String): Boolean =
        currentIdentity?.hasPermission(permission) ?: false

    /**
     * 检查用户是否具有任意一个指定权限
     */
    fun hasAnyPermission(vararg permissions: String): Boolean =
        currentIdentity?.hasAnyPermission(*permissions) ?: false

    /**
     * 检查用户是否具有所有指定权限
     */
    fun hasAllPermissions(vararg permissions: String): Boolean =
        currentIdentity?.hasAllPermissions(*permissions) ?: false

    /**
     * 获取用户ID
     */
    fun userId(): String? = currentIdentity?.id

    /**
     * 获取用户角色集合
     */
    fun userRoles(): Set<String> = currentIdentity?.roles ?: emptySet()

    /**
     * 获取用户权限集合
     */
    fun userPermissions(): Set<String> = currentIdentity?.permissions ?: emptySet()

    /**
     * 清空当前安全上下文
     */
    fun clear() {
        currentIdentity = null
    }

    /**
     * 要求用户已认证，否则抛出异常
     */
    fun requireAuthenticated(): Identity {
        return currentIdentity ?: throw IllegalStateException("Authentication required")
    }

    /**
     * 要求用户具有指定角色，否则抛出异常
     */
    fun requireRole(role: String) {
        if (!hasRole(role)) {
            throw IllegalStateException("Role '$role' required")
        }
    }

    /**
     * 要求用户具有任意一个指定角色，否则抛出异常
     */
    fun requireAnyRole(vararg roles: String) {
        if (!hasAnyRole(*roles)) {
            val roleList = roles.joinToString(", ")
            throw IllegalStateException("One of roles [$roleList] required")
        }
    }

    /**
     * 要求用户具有指定权限，否则抛出异常
     */
    fun requirePermission(permission: String) {
        if (!hasPermission(permission)) {
            throw IllegalStateException("Permission '$permission' required")
        }
    }
}
