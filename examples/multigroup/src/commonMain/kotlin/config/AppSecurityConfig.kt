package config

import neton.core.component.NetonContext
import neton.core.config.NetonConfig
import neton.core.config.SecurityConfigurer
import neton.core.interfaces.SecurityBuilder
import neton.security.RealAnonymousGuard
import neton.security.RealDefaultGuard

/**
 * 业务层安全配置 - 直接调用 SecurityBuilder API，调试友好
 * 由 KSP 自动发现并应用，ctx 可访问其他 Service 供高级场景使用
 */
@NetonConfig(component = "security", order = 0)
class AppSecurityConfig : SecurityConfigurer {

    override fun configure(ctx: NetonContext, security: SecurityBuilder) {
        // 默认组：开放（匿名守卫 = 允许所有）
        security.setDefaultGuard(RealAnonymousGuard())

        // admin 组：Mock 认证 + 默认守卫（requireAuth 时生效）
        val factory = security.getSecurityFactory()
        security.setGroupAuthenticator("admin", factory.createAuthenticator("mock", mapOf(
            "userId" to "admin-user",
            "roles" to listOf("admin"),
            "attributes" to emptyMap<String, Any>()
        )))
        security.setGroupGuard("admin", RealDefaultGuard())

        // app 组：Mock 认证 + 默认守卫
        security.setGroupAuthenticator("app", factory.createAuthenticator("mock", mapOf(
            "userId" to "app-user",
            "roles" to listOf("user"),
            "attributes" to emptyMap<String, Any>()
        )))
        security.setGroupGuard("app", RealDefaultGuard())
    }
}
