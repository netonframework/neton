package neton.core.security

import neton.core.interfaces.Identity

/**
 * 安全上下文 - 全局用户访问
 */
object SecurityContext {
    private var currentIdentity: Identity? = null

    fun setIdentity(identity: Identity?) {
        currentIdentity = identity
    }

    fun currentUser(): Identity? = currentIdentity

    fun isAuthenticated(): Boolean = currentIdentity != null

    fun hasRole(role: String): Boolean = currentUser()?.hasRole(role) ?: false
    fun hasAnyRole(vararg roles: String): Boolean = currentUser()?.hasAnyRole(*roles) ?: false
    fun hasAllRoles(vararg roles: String): Boolean = currentUser()?.hasAllRoles(*roles) ?: false

    fun hasPermission(permission: String): Boolean = currentUser()?.hasPermission(permission) ?: false
    fun hasAnyPermission(vararg ps: String): Boolean = currentUser()?.hasAnyPermission(*ps) ?: false
    fun hasAllPermissions(vararg ps: String): Boolean = currentUser()?.hasAllPermissions(*ps) ?: false

    fun userId(): String? = currentUser()?.id
    fun userRoles(): Set<String> = currentUser()?.roles ?: emptySet()
    fun userPermissions(): Set<String> = currentUser()?.permissions ?: emptySet()

    fun clear() {
        currentIdentity = null
    }

    fun requireAuthenticated(): Identity {
        return currentUser() ?: throw IllegalStateException("Authentication required")
    }

    fun requireRole(role: String) {
        if (!hasRole(role)) throw IllegalStateException("Role '$role' required")
    }

    fun requirePermission(permission: String) {
        if (!hasPermission(permission)) throw IllegalStateException("Permission '$permission' required")
    }

    fun requireAnyRole(vararg roles: String) {
        if (!hasAnyRole(*roles)) {
            throw IllegalStateException("One of roles [${roles.joinToString(", ")}] required")
        }
    }
}
