package neton.core.mock

import neton.core.CoreLog
import neton.core.interfaces.*
import neton.core.http.HttpContext
import neton.core.http.HttpMethod
import neton.core.security.AuthenticationContext

/**
 * Mock 请求处理引擎 - Core 模块默认实现
 */
class MockRequestEngine : RequestEngine {

    private val routes = mutableListOf<RouteDefinition>()
    private var authContext: AuthenticationContext? = null

    override suspend fun processRequest(context: HttpContext): Any? {
        CoreLog.logOrBootstrap().warn("neton.mock.request_engine", mapOf("hint" to "No routing module found"))

        for (route in routes) {
            if (route.pattern == context.request.path && route.method == context.request.method) {
                return route.handler.invoke(context, neton.core.http.MapBackedHandlerArgs(emptyMap()))
            }
        }

        context.response.status = neton.core.http.HttpStatus.NOT_FOUND
        return "404 - Route not found: ${context.request.method} ${context.request.path}"
    }

    override fun registerRoute(route: RouteDefinition) {
        routes.add(route)
        CoreLog.logOrBootstrap()
            .info("neton.mock.route.registered", mapOf("method" to route.method.name, "pattern" to route.pattern))
    }

    override fun getRoutes(): List<RouteDefinition> = routes.toList()

    override fun setAuthenticationContext(authContext: AuthenticationContext) {
        this.authContext = authContext
    }
}

/**
 * Mock Identity 实现
 */
data class MockIdentity(
    override val id: String,
    override val roles: Set<String> = emptySet(),
    override val permissions: Set<String> = emptySet()
) : Identity

/**
 * Mock 认证器
 */
class MockAuthenticator(
    private val mockUser: Identity? = MockIdentity("mock-user", setOf("user"))
) : Authenticator {
    override val name = "mock"
    override suspend fun authenticate(context: RequestContext): Identity? = mockUser
}

/**
 * Mock 默认守卫
 */
class MockDefaultGuard : Guard {
    override val name = "default"
    override suspend fun checkPermission(identity: Identity?, context: RequestContext): Boolean {
        return identity != null
    }
}

/**
 * Mock 管理员守卫
 */
class MockAdminGuard : Guard {
    override val name = "admin"
    override suspend fun checkPermission(identity: Identity?, context: RequestContext): Boolean {
        return identity?.hasRole("admin") == true
    }
}

/**
 * Mock 角色守卫
 */
class MockRoleGuard(private val allowedRoles: Array<String>) : Guard {
    override val name = "role"
    override suspend fun checkPermission(identity: Identity?, context: RequestContext): Boolean {
        return identity?.hasAnyRole(*allowedRoles) == true
    }
}

/**
 * Mock 匿名守卫
 */
class MockAnonymousGuard : Guard {
    override val name = "anonymous"
    override suspend fun checkPermission(identity: Identity?, context: RequestContext): Boolean = true
}

/**
 * Mock 认证上下文
 */
class MockAuthenticationContext : AuthenticationContext {
    override fun currentUser(): Any? = null
}

/**
 * Mock 安全构建器
 */
class MockSecurityBuilder : SecurityBuilder {

    private val authenticators = mutableListOf<Authenticator>()
    private val guards = mutableListOf<Guard>()
    private var permissionEval: PermissionEvaluator? = null

    override fun registerMockAuthenticator(userId: String, roles: Set<String>, permissions: Set<String>) {
        val authenticator = MockAuthenticator(MockIdentity(userId, roles, permissions))
        authenticators.add(authenticator)
        CoreLog.logOrBootstrap().info("neton.mock.authenticator", mapOf("userId" to userId))
    }

    override fun registerMockAuthenticator(name: String, userId: String, roles: Set<String>, permissions: Set<String>) {
        registerMockAuthenticator(userId, roles, permissions)
        CoreLog.logOrBootstrap().info("neton.mock.authenticator.named", mapOf("name" to name))
    }

    override fun registerJwtAuthenticator(secretKey: String, headerName: String, tokenPrefix: String) {
        CoreLog.logOrBootstrap().warn("neton.mock.jwt.unavailable")
    }

    override fun registerSessionAuthenticator(sessionKey: String) {
        CoreLog.logOrBootstrap().warn("neton.mock.session.unavailable")
    }

    override fun registerBasicAuthenticator(userProvider: suspend (username: String, password: String) -> Identity?) {
        CoreLog.logOrBootstrap().warn("neton.mock.basic.unavailable")
    }

    override fun bindDefaultGuard() {
        guards.add(MockDefaultGuard())
    }

    override fun bindAdminGuard() {
        guards.add(MockAdminGuard())
    }

    override fun bindRoleGuard(vararg roles: String) {
        guards.add(MockRoleGuard(arrayOf(*roles)))
    }

    override fun bindNamedRoleGuard(name: String, vararg roles: String) {
        bindRoleGuard(*roles)
    }

    override fun bindAnonymousGuard() {
        guards.add(MockAnonymousGuard())
    }

    override fun registerAuthenticator(authenticator: Authenticator) {
        authenticators.add(authenticator)
    }

    override fun registerAuthenticator(name: String, authenticator: Authenticator) {
        authenticators.add(authenticator)
    }

    override fun bindGuard(guard: Guard) {
        guards.add(guard)
    }

    override fun bindGuard(name: String, guard: Guard) {
        guards.add(guard)
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

    override fun setPermissionEvaluator(evaluator: PermissionEvaluator) {
        permissionEval = evaluator
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
            authenticationContext = MockAuthenticationContext(),
            permissionEvaluator = permissionEval
        )
    }

    override fun getAuthenticationContext(): AuthenticationContext {
        return MockAuthenticationContext()
    }
}

/**
 * Mock 路由处理器
 */
class MockRouteHandler(private val response: String) : RouteHandler {
    override suspend fun invoke(context: HttpContext, args: neton.core.http.HandlerArgs): Any? = response
}
