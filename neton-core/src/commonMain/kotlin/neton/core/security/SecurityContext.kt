package neton.core.security

import neton.core.interfaces.Identity
import neton.core.interfaces.SecurityAttributes
import neton.core.http.HttpContext

/**
 * Request-bound identity view. Never retain this object beyond its request lifetime.
 */
class SecurityContext(private val context: HttpContext) : AuthenticationContext {

    fun setIdentity(identity: Identity?) {
        if (identity == null) clear() else context.setAttribute(SecurityAttributes.IDENTITY, identity)
    }

    override fun currentUser(): Identity? = context.getAttribute(SecurityAttributes.IDENTITY) as? Identity

    fun currentIdentity(): Identity? = currentUser()

    fun isAuthenticated(): Boolean = currentUser() != null

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
        context.removeAttribute(SecurityAttributes.IDENTITY)
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
