package neton.security

import neton.core.interfaces.Guard

/**
 * 安全配置构建器 - 提供 DSL 风格的配置接口
 */
class SecurityBuilder {
    private val registry = SecurityRegistry()

    /**
     * 注册默认认证器
     */
    fun registerAuthenticator(authenticator: Authenticator) {
        registry.registerAuthenticator(authenticator)
    }

    /**
     * 注册路由组认证器
     */
    fun registerAuthenticator(routeGroup: String, authenticator: Authenticator) {
        registry.registerAuthenticator(routeGroup, authenticator)
    }

    /**
     * 绑定默认守卫
     */
    fun bindGuard(guard: Guard) {
        registry.bindGuard(guard)
    }

    /**
     * 绑定路由组守卫
     */
    fun bindGuard(routeGroup: String, guard: Guard) {
        registry.bindGuard(routeGroup, guard)
    }

    /**
     * 构建安全注册表
     */
    internal fun build(): SecurityRegistry {
        return registry
    }
}

/**
 * 安全配置 DSL 入口函数
 */
fun security(configure: SecurityBuilder.() -> Unit): SecurityRegistry {
    val builder = SecurityBuilder()
    builder.configure()
    return builder.build()
}
