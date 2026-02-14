package neton.core.interfaces

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
 * 身份验证器接口 - 专注于验证用户身份
 */
interface Authenticator {
    /**
     * 执行身份验证
     * @param context 请求上下文
     * @return 验证成功返回 Principal，失败返回 null
     */
    suspend fun authenticate(context: RequestContext): Principal?
    
    /**
     * 认证器名称
     */
    val name: String
}

/**
 * 权限守卫接口 - 专注于权限检查
 */
interface Guard {
    /**
     * 检查权限
     * @param principal 用户主体
     * @param context 请求上下文
     * @return true 表示有权限，false 表示无权限
     */
    suspend fun checkPermission(principal: Principal?, context: RequestContext): Boolean
    
    /**
     * 守卫名称
     */
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
 * 安全工厂接口 - 用于创建安全组件实例
 */
interface SecurityFactory {
    
    /**
     * 创建认证器
     */
    fun createAuthenticator(type: String, config: Map<String, Any> = mapOf()): Authenticator
    
    /**
     * 创建守卫
     */
    fun createGuard(type: String, config: Map<String, Any> = mapOf()): Guard
    
    /**
     * 创建用户主体
     */
    fun createPrincipal(id: String, roles: List<String>, attributes: Map<String, Any> = mapOf()): Principal
} 