package neton.security

/**
 * 安全注册表 - 管理认证器和守卫的绑定关系
 */
class SecurityRegistry {
    private val authenticators = mutableMapOf<String, Authenticator>()
    private val guards = mutableMapOf<String, Guard>()
    private var defaultAuthenticator: Authenticator? = null
    private var defaultGuard: Guard? = null
    
    /**
     * 注册默认认证器
     */
    fun registerAuthenticator(authenticator: Authenticator) {
        defaultAuthenticator = authenticator
    }
    
    /**
     * 注册路由组认证器
     */
    fun registerAuthenticator(routeGroup: String, authenticator: Authenticator) {
        authenticators[routeGroup] = authenticator
    }
    
    /**
     * 绑定默认守卫
     */
    fun bindGuard(guard: Guard) {
        defaultGuard = guard
    }
    
    /**
     * 绑定路由组守卫
     */
    fun bindGuard(routeGroup: String, guard: Guard) {
        guards[routeGroup] = guard
    }
    
    /**
     * 获取路由组对应的认证器
     * @param routeGroup 路由组名称，null 表示默认路由组
     * @return 对应的认证器，如果没有配置则返回 null
     */
    fun getAuthenticator(routeGroup: String?): Authenticator? {
        return if (routeGroup == null || routeGroup == "default") {
            defaultAuthenticator
        } else {
            authenticators[routeGroup] ?: defaultAuthenticator
        }
    }
    
    /**
     * 获取路由组对应的守卫
     * @param routeGroup 路由组名称，null 表示默认路由组
     * @return 对应的守卫，如果没有配置则返回 null
     */
    fun getGuard(routeGroup: String?): Guard? {
        return if (routeGroup == null || routeGroup == "default") {
            defaultGuard
        } else {
            guards[routeGroup] ?: defaultGuard
        }
    }
    
    /**
     * 检查是否有任何认证器配置
     */
    fun hasAnyAuthenticator(): Boolean {
        return defaultAuthenticator != null || authenticators.isNotEmpty()
    }
    
    /**
     * 检查是否有任何守卫配置
     */
    fun hasAnyGuard(): Boolean {
        return defaultGuard != null || guards.isNotEmpty()
    }
    
    /**
     * 获取所有配置的路由组
     */
    fun getAllRouteGroups(): Set<String> {
        return (authenticators.keys + guards.keys).toSet()
    }
    
    /**
     * 获取所有认证器绑定关系
     */
    fun getAllAuthenticators(): Map<String, Authenticator> = authenticators.toMap()
    
    /**
     * 获取所有守卫绑定关系
     */
    fun getAllGuards(): Map<String, Guard> = guards.toMap()
    
    /**
     * 清空所有配置
     */
    fun clear() {
        authenticators.clear()
        guards.clear()
        defaultAuthenticator = null
        defaultGuard = null
    }
} 