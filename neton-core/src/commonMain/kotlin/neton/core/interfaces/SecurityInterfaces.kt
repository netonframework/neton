package neton.core.interfaces

/**
 * 身份接口 — 代表已认证的用户
 *
 * roles 与 permissions 均为一等公民：
 * - roles：粗粒度分组（admin, user, ops），用于 @RolesAllowed
 * - permissions：细粒度动作（system:user:edit），用于 @Permission
 *
 * @see Neton-Security-Spec-v1.1-API-Freeze.md
 */
interface Identity {
    val id: String
    val roles: Set<String>
    val permissions: Set<String>

    fun hasRole(role: String): Boolean = role in roles
    fun hasPermission(p: String): Boolean = p in permissions

    fun hasAnyRole(vararg rs: String): Boolean = rs.any { it in roles }
    fun hasAllRoles(vararg rs: String): Boolean = rs.all { it in roles }
    fun hasAnyPermission(vararg ps: String): Boolean = ps.any { it in permissions }
    fun hasAllPermissions(vararg ps: String): Boolean = ps.all { it in permissions }
}

/**
 * 身份验证器接口
 */
interface Authenticator {
    suspend fun authenticate(context: RequestContext): Identity?
    val name: String
}

/**
 * 权限守卫接口
 */
interface Guard {
    suspend fun checkPermission(identity: Identity?, context: RequestContext): Boolean
    val name: String
}

/**
 * 请求上下文接口
 */
interface RequestContext {
    val path: String
    val method: String
    val headers: Map<String, String>
    val routeGroup: String?
}

/**
 * 权限评估器 — 业务可替换实现（如 superadmin 逻辑）
 * 默认实现：permission in identity.permissions
 */
fun interface PermissionEvaluator {
    fun allowed(identity: Identity, permission: String, context: RequestContext): Boolean
}

/**
 * 安全属性键名常量 — v1.2 冻结
 * 全链路统一引用，禁止硬编码字符串
 */
object SecurityAttributes {
    /** HttpContext attribute key for the authenticated Identity */
    const val IDENTITY = "identity"
}
