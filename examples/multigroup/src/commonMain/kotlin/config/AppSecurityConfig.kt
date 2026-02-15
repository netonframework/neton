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
        security.registerMockAuthenticator("admin", "admin-user", setOf("admin"), emptySet())
        security.setGroupGuard("admin", RealDefaultGuard())

        // app 组：Mock 认证 + 默认守卫
        security.registerMockAuthenticator("app", "app-user", setOf("user"), emptySet())
        security.setGroupGuard("app", RealDefaultGuard())
    }
}
