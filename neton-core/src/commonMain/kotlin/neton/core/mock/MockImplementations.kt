package neton.core.mock

import neton.core.CoreLog
import neton.core.interfaces.*
import neton.core.http.HttpContext
import neton.core.http.HttpMethod
import neton.core.security.AuthenticationContext

/**
 * 内存路由注册表 —— 供生命周期类测试在不装 neton-routing 的情况下满足 RequestEngine 依赖。
 */
class MockRequestEngine : RequestEngine {

    private val routes = mutableListOf<RouteDefinition>()

    override fun registerRoute(route: RouteDefinition) {
        routes.add(route)
        CoreLog.logOrBootstrap()
            .info("neton.mock.route.registered", mapOf("method" to route.method.name, "pattern" to route.pattern))
    }

    override fun getRoutes(): List<RouteDefinition> = routes.toList()
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
 * Mock 路由处理器
 */
class MockRouteHandler(private val response: String) : RouteHandler {
    override suspend fun invoke(context: HttpContext, args: neton.core.http.HandlerArgs): Any? = response
}
