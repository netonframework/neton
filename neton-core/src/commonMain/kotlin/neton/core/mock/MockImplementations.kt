package neton.core.mock

import neton.core.CoreLog
import neton.core.interfaces.*
import neton.core.http.HttpContext
import neton.core.http.HttpMethod
import neton.core.security.AuthenticationContext

/**
 * Mock 请求处理引擎 - Core 模块默认实现
 * 
 * 当没有实际的 routing 模块时使用此实现
 */
class MockRequestEngine : RequestEngine {
    
    private val routes = mutableListOf<RouteDefinition>()
    private var authContext: AuthenticationContext? = null
    
    override suspend fun processRequest(context: HttpContext): Any? {
        CoreLog.logOrBootstrap().warn("neton.mock.request_engine", mapOf("hint" to "No routing module found"))
        
        // 简单的路由匹配
        for (route in routes) {
            if (route.pattern == context.request.path && route.method == context.request.method) {
                return route.handler.invoke(context, neton.core.http.MapBackedHandlerArgs(emptyMap()))
            }
        }
        
        // 默认 404 响应
        context.response.status = neton.core.http.HttpStatus.NOT_FOUND
        return "404 - Route not found: ${context.request.method} ${context.request.path}"
    }
    
    override fun registerRoute(route: RouteDefinition) {
        routes.add(route)
        CoreLog.logOrBootstrap().info("neton.mock.route.registered", mapOf("method" to route.method.name, "pattern" to route.pattern))
    }
    
    override fun getRoutes(): List<RouteDefinition> = routes.toList()
    
    override fun setAuthenticationContext(authContext: AuthenticationContext) {
        this.authContext = authContext
    }
}

/**
 * Mock 安全工厂 - Core 模块默认实现
 */
class MockSecurityFactory : SecurityFactory {
    
    override fun createAuthenticator(type: String, config: Map<String, Any>): Authenticator {
        return when (type) {
            "mock" -> MockAuthenticator(
                mockUser = createPrincipal(
                    config["userId"] as? String ?: "mock-user",
                    config["roles"] as? List<String> ?: listOf("user"),
                    config["attributes"] as? Map<String, Any> ?: mapOf()
                )
            )
            else -> MockAuthenticator()
        }
    }
    
    override fun createGuard(type: String, config: Map<String, Any>): Guard {
        return when (type) {
            "default" -> MockDefaultGuard()
            "admin" -> MockAdminGuard()
            "role" -> MockRoleGuard(config["roles"] as? Array<String> ?: arrayOf("user"))
            "anonymous" -> MockAnonymousGuard()
            else -> MockDefaultGuard()
        }
    }
    
    override fun createPrincipal(id: String, roles: List<String>, attributes: Map<String, Any>): Principal {
        return MockPrincipal(id, roles, attributes)
    }
}

/**
 * Mock 安全构建器 - Core 模块默认实现
 * 
 * 当没有实际的 security 模块时使用此实现
 */
class MockSecurityBuilder : SecurityBuilder {
    
    private val authenticators = mutableListOf<Authenticator>()
    private val guards = mutableListOf<Guard>()
    private val securityFactory = MockSecurityFactory()
    
    override fun getSecurityFactory(): SecurityFactory = securityFactory
    
    // ===== 认证器配置方法 =====
    
    override fun registerMockAuthenticator(userId: String, roles: List<String>, attributes: Map<String, Any>) {
        val authenticator = securityFactory.createAuthenticator("mock", mapOf(
            "userId" to userId,
            "roles" to roles,
            "attributes" to attributes
        ))
        authenticators.add(authenticator)
        CoreLog.logOrBootstrap().info("neton.mock.authenticator", mapOf("userId" to userId))
    }

    override fun registerMockAuthenticator(name: String, userId: String, roles: List<String>, attributes: Map<String, Any>) {
        registerMockAuthenticator(userId, roles, attributes)
        CoreLog.logOrBootstrap().info("neton.mock.authenticator.named", mapOf("name" to name))
    }

    override fun registerJwtAuthenticator(secretKey: String, headerName: String, tokenPrefix: String) {
        CoreLog.logOrBootstrap().warn("neton.mock.jwt.unavailable")
    }

    override fun registerSessionAuthenticator(sessionKey: String) {
        CoreLog.logOrBootstrap().warn("neton.mock.session.unavailable")
    }

    override fun registerBasicAuthenticator(userProvider: suspend (username: String, password: String) -> Principal?) {
        CoreLog.logOrBootstrap().warn("neton.mock.basic.unavailable")
    }

    override fun bindDefaultGuard() {
        val guard = securityFactory.createGuard("default", mapOf())
        guards.add(guard)
        CoreLog.logOrBootstrap().info("neton.mock.guard.default")
    }

    override fun bindAdminGuard() {
        val guard = securityFactory.createGuard("admin", mapOf())
        guards.add(guard)
        CoreLog.logOrBootstrap().info("neton.mock.guard.admin")
    }

    override fun bindRoleGuard(vararg roles: String) {
        val guard = securityFactory.createGuard("role", mapOf("roles" to roles))
        guards.add(guard)
        CoreLog.logOrBootstrap().info("neton.mock.guard.role", mapOf("roles" to roles.toList()))
    }

    override fun bindNamedRoleGuard(name: String, vararg roles: String) {
        bindRoleGuard(*roles)
        CoreLog.logOrBootstrap().info("neton.mock.guard.named", mapOf("name" to name))
    }

    override fun bindAnonymousGuard() {
        val guard = securityFactory.createGuard("anonymous", mapOf())
        guards.add(guard)
        CoreLog.logOrBootstrap().info("neton.mock.guard.anonymous")
    }

    override fun registerAuthenticator(authenticator: Authenticator) {
        authenticators.add(authenticator)
        CoreLog.logOrBootstrap().info("neton.mock.authenticator.custom", mapOf("name" to authenticator.name))
    }

    override fun registerAuthenticator(name: String, authenticator: Authenticator) {
        authenticators.add(authenticator)
        CoreLog.logOrBootstrap().info("neton.mock.authenticator.named", mapOf("name" to name))
    }

    override fun bindGuard(guard: Guard) {
        guards.add(guard)
        CoreLog.logOrBootstrap().info("neton.mock.guard.custom", mapOf("name" to guard.name))
    }

    override fun bindGuard(name: String, guard: Guard) {
        guards.add(guard)
        CoreLog.logOrBootstrap().info("neton.mock.guard.named", mapOf("name" to name))
    }

    override fun setDefaultAuthenticator(auth: Authenticator?) {
        if (auth != null) {
            authenticators.clear()
            authenticators.add(auth)
        }
    }

    override fun setDefaultGuard(guard: Guard) {
        guards.clear()
        guards.add(guard)
    }

    override fun setGroupAuthenticator(group: String, auth: Authenticator?) {
        if (group.isBlank()) throw IllegalArgumentException("Security group name must not be blank")
        if (auth != null) authenticators.add(auth)
    }

    override fun setGroupGuard(group: String, guard: Guard) {
        if (group.isBlank()) throw IllegalArgumentException("Security group name must not be blank")
        guards.add(guard)
    }

    override fun getGroupConfigSummary(): List<SecurityGroupConfig> {
        return listOf(SecurityGroupConfig(group = null, authenticator = null, guard = "<mock>"))
    }

    override fun build(): SecurityConfiguration {
        CoreLog.logOrBootstrap().warn("neton.mock.security_builder", mapOf("hint" to "No security module found"))
        return SecurityConfiguration(
            isEnabled = authenticators.isNotEmpty() || guards.isNotEmpty(),
            authenticatorCount = authenticators.size,
            guardCount = guards.size,
            authenticationContext = MockAuthenticationContext()
        )
    }
    
    override fun getAuthenticationContext(): AuthenticationContext {
        return MockAuthenticationContext()
    }
}

// ===== Mock 安全组件实现 =====

/**
 * Mock 用户主体
 */
data class MockPrincipal(
    override val id: String,
    override val roles: List<String>,
    override val attributes: Map<String, Any> = mapOf()
) : Principal

/**
 * Mock 认证器
 */
class MockAuthenticator(
    private val mockUser: Principal = MockPrincipal("mock-user", listOf("user"))
) : Authenticator {
    override val name = "mock"
    
    override suspend fun authenticate(context: RequestContext): Principal = mockUser
}

/**
 * Mock 默认守卫
 */
class MockDefaultGuard : Guard {
    override val name = "default"
    
    override suspend fun checkPermission(principal: Principal?, context: RequestContext): Boolean {
        return principal != null // 只要已认证就允许
    }
}

/**
 * Mock 管理员守卫
 */
class MockAdminGuard : Guard {
    override val name = "admin"
    
    override suspend fun checkPermission(principal: Principal?, context: RequestContext): Boolean {
        return principal?.hasRole("admin") == true
    }
}

/**
 * Mock 角色守卫
 */
class MockRoleGuard(private val allowedRoles: Array<String>) : Guard {
    override val name = "role"
    
    override suspend fun checkPermission(principal: Principal?, context: RequestContext): Boolean {
        return principal?.hasAnyRole(*allowedRoles) == true
    }
}

/**
 * Mock 匿名守卫
 */
class MockAnonymousGuard : Guard {
    override val name = "anonymous"
    
    override suspend fun checkPermission(principal: Principal?, context: RequestContext): Boolean {
        return true // 总是允许
    }
}

/**
 * Mock 认证上下文 - Core 模块默认实现
 * 
 * 当没有实际的 security 模块时使用此实现
 */
class MockAuthenticationContext : AuthenticationContext {
    
    override fun currentUser(): Any? {
        // Mock 实现总是返回匿名用户
        return null
    }
}

/**
 * Mock 路由处理器 - Core 模块默认实现
 */
class MockRouteHandler(private val response: String) : RouteHandler {
    override suspend fun invoke(context: HttpContext, args: neton.core.http.HandlerArgs): Any? = response
}
