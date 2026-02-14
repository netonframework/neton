package neton.security

/**
 * 用户主体接口 - 代表已认证的用户
 */
interface Principal {
    val id: String
    val roles: List<String>
    val attributes: Map<String, Any> get() = mapOf()
    
    /**
     * 检查用户是否具有指定角色
     */
    fun hasRole(role: String): Boolean = roles.contains(role)
    
    /**
     * 检查用户是否具有任意一个指定角色
     */
    fun hasAnyRole(vararg roles: String): Boolean = roles.any { hasRole(it) }
    
    /**
     * 检查用户是否具有所有指定角色
     */
    fun hasAllRoles(vararg roles: String): Boolean = roles.all { hasRole(it) }
}

/**
 * 用户主体实现类
 */
data class UserPrincipal(
    override val id: String,
    override val roles: List<String>,
    override val attributes: Map<String, Any> = mapOf()
) : Principal

/**
 * 匿名用户主体
 */
object AnonymousPrincipal : Principal {
    override val id: String = "anonymous"
    override val roles: List<String> = listOf()
    override val attributes: Map<String, Any> = mapOf()
} 