package neton.core.security

import neton.core.interfaces.Principal

/**
 * 安全上下文 - 提供 Spring Security 风格的全局用户访问
 */
object SecurityContext {
    // 使用 ThreadLocal 在多线程环境中保存当前用户信息
    // 注意：在 Kotlin Native 中可能需要其他实现方式
    private var currentPrincipal: Principal? = null
    
    /**
     * 设置当前用户主体
     * @param principal 用户主体，null 表示未认证
     */
    fun setPrincipal(principal: Principal?) {
        currentPrincipal = principal
    }
    
    /**
     * 获取当前用户
     * @return 当前用户主体，未认证时返回 null
     */
    fun currentUser(): Principal? = currentPrincipal
    
    /**
     * 检查用户是否已认证
     * @return true 如果用户已认证
     */
    fun isAuthenticated(): Boolean = currentUser() != null
    
    /**
     * 检查用户是否具有指定角色
     * @param role 角色名称
     * @return true 如果用户具有该角色
     */
    fun hasRole(role: String): Boolean = 
        currentUser()?.hasRole(role) ?: false
        
    /**
     * 检查用户是否具有任意一个指定角色
     * @param roles 角色名称列表
     * @return true 如果用户具有任意一个角色
     */
    fun hasAnyRole(vararg roles: String): Boolean = 
        currentUser()?.hasAnyRole(*roles) ?: false
        
    /**
     * 检查用户是否具有所有指定角色
     * @param roles 角色名称列表
     * @return true 如果用户具有所有角色
     */
    fun hasAllRoles(vararg roles: String): Boolean = 
        currentUser()?.hasAllRoles(*roles) ?: false
        
    /**
     * 检查用户是否具有指定权限
     * @param permission 权限名称
     * @return true 如果用户具有该权限
     */
    fun hasPermission(permission: String): Boolean {
        val user = currentUser() ?: return false
        val permissions = user.attributes["permissions"] as? List<*>
        return permissions?.contains(permission) ?: false
    }
    
    /**
     * 获取用户属性
     * @param key 属性键
     * @return 属性值，不存在时返回 null
     */
    fun getAttribute(key: String): Any? = currentUser()?.attributes?.get(key)
    
    /**
     * 获取用户ID
     * @return 用户ID，未认证时返回 null
     */
    fun userId(): String? = currentUser()?.id
    
    /**
     * 获取用户角色列表
     * @return 用户角色列表，未认证时返回空列表
     */
    fun userRoles(): List<String> = currentUser()?.roles ?: listOf()
    
    /**
     * 清空当前安全上下文
     */
    fun clear() {
        currentPrincipal = null
    }
    
    /**
     * 要求用户已认证，否则抛出异常
     * @throws SecurityException 如果用户未认证
     * @return 当前用户主体
     */
    fun requireAuthenticated(): Principal {
        return currentUser() ?: throw IllegalStateException("Authentication required")
    }
    
    /**
     * 要求用户具有指定角色，否则抛出异常
     * @param role 必需的角色
     * @throws SecurityException 如果用户没有该角色
     */
    fun requireRole(role: String) {
        if (!hasRole(role)) {
            throw IllegalStateException("Role '$role' required")
        }
    }
    
    /**
     * 要求用户具有任意一个指定角色，否则抛出异常
     * @param roles 角色列表
     * @throws SecurityException 如果用户没有任何指定角色
     */
    fun requireAnyRole(vararg roles: String) {
        if (!hasAnyRole(*roles)) {
            val roleList = roles.joinToString(", ")
            throw IllegalStateException("One of roles [$roleList] required")
        }
    }
} 