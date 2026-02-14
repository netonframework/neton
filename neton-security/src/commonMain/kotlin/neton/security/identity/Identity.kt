package neton.security.identity

/**
 * 当前登录用户的身份抽象
 *
 * @see Neton-Security-Spec-v1.1-API-Freeze.md
 */
interface Identity {
    val id: UserId
    val roles: Set<String>
    val permissions: Set<String>

    fun hasRole(role: String): Boolean = role in roles
    fun hasPermission(p: String): Boolean = p in permissions

    fun hasAnyRole(vararg rs: String): Boolean = rs.any { it in roles }
    fun hasAllRoles(vararg rs: String): Boolean = rs.all { it in roles }
    fun hasAnyPermission(vararg ps: String): Boolean = ps.any { it in permissions }
    fun hasAllPermissions(vararg ps: String): Boolean = ps.all { it in permissions }
}
