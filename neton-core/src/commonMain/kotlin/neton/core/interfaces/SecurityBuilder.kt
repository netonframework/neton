package neton.core.interfaces

import neton.core.security.AuthenticationContext

/**
 * 路由组安全配置 - 用于启动期审计/日志
 */
data class SecurityGroupConfig(
    val group: String?,
    val authenticator: String?,
    val guard: String
)

/**
 * 安全构建器接口 - Core 模块定义的标准接口
 * 
 * 使用工厂模式，用户通过方法配置安全组件，而不直接依赖具体实现
 */
interface SecurityBuilder {
    
    /**
     * 获取安全工厂
     */
    fun getSecurityFactory(): SecurityFactory
    
    // ===== 认证器配置方法 =====
    
    /**
     * 注册模拟认证器 - 开发测试使用
     */
    fun registerMockAuthenticator(userId: String, roles: List<String>, attributes: Map<String, Any> = mapOf())
    
    /**
     * 注册命名模拟认证器
     */
    fun registerMockAuthenticator(name: String, userId: String, roles: List<String>, attributes: Map<String, Any> = mapOf())
    
    /**
     * 注册 JWT 认证器
     */
    fun registerJwtAuthenticator(secretKey: String, headerName: String = "Authorization", tokenPrefix: String = "Bearer ")
    
    /**
     * 注册会话认证器
     */
    fun registerSessionAuthenticator(sessionKey: String = "user_id")
    
    /**
     * 注册 Basic 认证器
     */
    fun registerBasicAuthenticator(userProvider: suspend (username: String, password: String) -> Principal?)
    
    // ===== 守卫配置方法 =====
    
    /**
     * 绑定默认守卫 - 允许所有已认证用户
     */
    fun bindDefaultGuard()
    
    /**
     * 绑定管理员守卫 - 只允许管理员角色
     */
    fun bindAdminGuard()
    
    /**
     * 绑定角色守卫 - 允许指定角色
     */
    fun bindRoleGuard(vararg roles: String)
    
    /**
     * 绑定命名角色守卫
     */
    fun bindNamedRoleGuard(name: String, vararg roles: String)
    
    /**
     * 绑定匿名守卫 - 允许匿名访问
     */
    fun bindAnonymousGuard()
    
    // ===== 组配置 API（推荐：配置类直接调用，调试友好） =====
    
    /**
     * 设置默认组认证器（routeGroup = null）
     */
    fun setDefaultAuthenticator(auth: Authenticator?)
    
    /**
     * 设置默认组守卫（routeGroup = null）
     */
    fun setDefaultGuard(guard: Guard)
    
    /**
     * 设置指定组的认证器
     * @throws IllegalArgumentException 当 group 为空或非法时 fail-fast
     */
    fun setGroupAuthenticator(group: String, auth: Authenticator?)
    
    /**
     * 设置指定组的守卫
     * @throws IllegalArgumentException 当 group 为空或非法时 fail-fast
     */
    fun setGroupGuard(group: String, guard: Guard)
    
    // ===== 通用方法（与 set* 等效，保留兼容） =====
    
    /**
     * 注册自定义认证器（默认组），等价于 setDefaultAuthenticator
     */
    fun registerAuthenticator(authenticator: Authenticator)
    
    /**
     * 注册命名自定义认证器（指定组），等价于 setGroupAuthenticator
     */
    fun registerAuthenticator(name: String, authenticator: Authenticator)
    
    /**
     * 绑定自定义守卫（默认组），等价于 setDefaultGuard
     */
    fun bindGuard(guard: Guard)
    
    /**
     * 绑定命名自定义守卫（指定组），等价于 setGroupGuard
     */
    fun bindGuard(name: String, guard: Guard)
    
    /**
     * 构建安全配置
     */
    fun build(): SecurityConfiguration
    
    /**
     * 获取认证上下文
     */
    fun getAuthenticationContext(): AuthenticationContext
    
    /**
     * 获取当前组配置（用于启动期审计日志）
     */
    fun getGroupConfigSummary(): List<SecurityGroupConfig>
}

/**
 * 安全配置 - Core 模块标准定义
 * @param getAuthenticatorByGroup 按 routeGroup 解析认证器；null 或返回 null 时使用 defaultAuthenticator
 * @param getGuardByGroup 按 routeGroup 解析守卫；null 或返回 null 时使用 defaultGuard
 */
data class SecurityConfiguration(
    val isEnabled: Boolean,
    val authenticatorCount: Int,
    val guardCount: Int,
    val authenticationContext: AuthenticationContext,
    val defaultAuthenticator: Authenticator? = null,  // 安全管道用，首个认证器
    val defaultGuard: Guard? = null,                 // 安全管道用，首个守卫
    val getAuthenticatorByGroup: ((String?) -> Authenticator?)? = null,
    val getGuardByGroup: ((String?) -> Guard?)? = null
)
