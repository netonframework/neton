package neton.security

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import neton.core.interfaces.*
import neton.core.security.AuthenticationContext

/**
 * 安全配置数据类 - SecurityComponent 专用
 */
data class SecurityConfig(
    val enabled: Boolean = true,
    val defaultAuth: String = "session"
) {
    fun printSummary() {
        SecurityLog.log?.info("security.config", mapOf("enabled" to enabled, "defaultAuth" to defaultAuth))
    }
}

/**
 * 安全构建器实现 - 实现 Core 模块 SecurityBuilder 接口
 */
class SecurityBuilderImpl : neton.core.interfaces.SecurityBuilder {

    private var logger: neton.logging.Logger? = null
    fun setLogger(log: neton.logging.Logger?) {
        logger = log
    }

    private var defaultAuthenticator: neton.core.interfaces.Authenticator? = null
    private var defaultGuard: neton.core.interfaces.Guard? = null
    private val authenticatorsByGroup = mutableMapOf<String, neton.core.interfaces.Authenticator>()
    private val guardsByGroup = mutableMapOf<String, neton.core.interfaces.Guard>()
    private var permissionEvaluator: PermissionEvaluator? = null

    // Tracks all registered authenticator names across default and groups to enforce uniqueness.
    private val registeredAuthenticatorNames = mutableSetOf<String>()

    private fun validateGroupName(group: String) {
        if (group.isBlank()) {
            throw IllegalArgumentException("Security group name must not be blank")
        }
    }

    /**
     * Enforces [Authenticator.name] uniqueness across all registrations.
     * Removing [old] frees its name slot before checking [new].
     */
    private fun trackAuthenticatorName(
        new: neton.core.interfaces.Authenticator?,
        old: neton.core.interfaces.Authenticator?
    ) {
        if (old != null) registeredAuthenticatorNames.remove(old.name)
        if (new != null) {
            require(new.name !in registeredAuthenticatorNames) {
                "Authenticator name '${new.name}' is already registered by another authenticator. " +
                "Each authenticator must have a unique name."
            }
            registeredAuthenticatorNames.add(new.name)
        }
    }

    override fun setDefaultAuthenticator(auth: neton.core.interfaces.Authenticator?) {
        trackAuthenticatorName(auth, defaultAuthenticator)
        defaultAuthenticator = auth
        logger?.info("security.set.default.authenticator", mapOf("name" to (auth?.name ?: "null")))
    }

    override fun setDefaultGuard(guard: neton.core.interfaces.Guard) {
        defaultGuard = guard
        logger?.info("security.set.default.guard", mapOf("name" to guard.name))
    }

    override fun setGroupAuthenticator(group: String, auth: neton.core.interfaces.Authenticator?) {
        validateGroupName(group)
        val existing = authenticatorsByGroup[group]
        if (existing != null) {
            logger?.warn("security.group.overwrite", mapOf("group" to group, "field" to "authenticator"))
        }
        trackAuthenticatorName(auth, existing)
        if (auth != null) authenticatorsByGroup[group] = auth
        else authenticatorsByGroup.remove(group)
        logger?.info("security.set.group.authenticator", mapOf("group" to group, "name" to (auth?.name ?: "null")))
    }

    override fun setGroupGuard(group: String, guard: neton.core.interfaces.Guard) {
        validateGroupName(group)
        if (guardsByGroup.containsKey(group)) {
            logger?.warn("security.group.overwrite", mapOf("group" to group, "field" to "guard"))
        }
        guardsByGroup[group] = guard
        logger?.info("security.set.group.guard", mapOf("group" to group, "name" to guard.name))
    }

    override fun getGroupConfigSummary(): List<SecurityGroupConfig> {
        val list = mutableListOf<SecurityGroupConfig>()
        list.add(
            SecurityGroupConfig(
                group = null,
                authenticator = defaultAuthenticator?.name,
                guard = defaultGuard?.name ?: "<none>"
            )
        )
        val allGroups = (authenticatorsByGroup.keys + guardsByGroup.keys).toSet().sorted()
        allGroups.forEach { g: String ->
            list.add(
                SecurityGroupConfig(
                    group = g,
                    authenticator = authenticatorsByGroup[g]?.name,
                    guard = guardsByGroup[g]?.name ?: "<none>"
                )
            )
        }
        return list
    }

    override fun registerMockAuthenticator(userId: String, roles: Set<String>, permissions: Set<String>) {
        val authenticator = MockAuthenticatorAdapter(userId, roles, permissions)
        setDefaultAuthenticator(authenticator)
        logger?.info("security.authenticator.mock", mapOf("userId" to userId))
    }

    override fun registerMockAuthenticator(name: String, userId: String, roles: Set<String>, permissions: Set<String>) {
        validateGroupName(name)
        val authenticator = MockAuthenticatorAdapter(userId, roles, permissions)
        setGroupAuthenticator(name, authenticator)
        logger?.info("security.authenticator.mock.named", mapOf("name" to name))
    }

    override fun registerJwtAuthenticator(secretKey: String, headerName: String, tokenPrefix: String) {
        val authenticator = JwtAuthenticatorAdapter(secretKey, headerName, tokenPrefix)
        setDefaultAuthenticator(authenticator)
        logger?.info("security.authenticator.jwt")
    }

    override fun registerSessionAuthenticator(sessionKey: String) {
        error("""
            Session authentication is not built-in in Neton v1.

            Reason:
            - Session requires a storage backend (Redis / DB), expiration policy,
              sliding renewal, CSRF binding, and cookie naming — all application-specific.
            - These cannot be standardized at framework level in v1.

            Solution:
            - Implement neton.core.interfaces.Authenticator directly.
            - Register it via security { registerAuthenticator(mySessionAuth) }.

            Example:
                class MySessionAuthenticator(private val redis: RedisClient) : Authenticator {
                    override val name = "session"
                    override suspend fun authenticate(context: RequestContext): Identity? {
                        val sessionId = context.headers["Cookie"]
                            ?.split(";")?.firstOrNull { it.trim().startsWith("sid=") }
                            ?.substringAfter("sid=")?.trim() ?: return null
                        return redis.getValue("session:${'$'}sessionId")?.let { parseIdentity(it) }
                    }
                }
        """.trimIndent())
    }

    override fun registerBasicAuthenticator(userProvider: suspend (username: String, password: String) -> Identity?) {
        val authenticator = BasicAuthenticatorAdapter(userProvider)
        setDefaultAuthenticator(authenticator)
        logger?.info("security.authenticator.basic")
    }

    // ===== 守卫配置方法 =====

    override fun bindDefaultGuard() {
        setDefaultGuard(DefaultGuardImpl())
        logger?.info("security.guard.default")
    }

    override fun bindAdminGuard() {
        setDefaultGuard(AdminGuardImpl())
        logger?.info("security.guard.admin")
    }

    override fun bindRoleGuard(vararg roles: String) {
        setDefaultGuard(RoleGuardImpl(*roles))
        logger?.info("security.guard.role", mapOf("roles" to roles.toList()))
    }

    override fun bindNamedRoleGuard(name: String, vararg roles: String) {
        val guard = RoleGuardImpl(*roles)
        setGroupGuard(name, guard)
        logger?.info("security.guard.named", mapOf("name" to name))
    }

    override fun bindAnonymousGuard() {
        setDefaultGuard(AnonymousGuardImpl())
        logger?.info("security.guard.anonymous")
    }

    override fun setPermissionEvaluator(evaluator: PermissionEvaluator) {
        permissionEvaluator = evaluator
        logger?.info("security.permissionEvaluator.set")
    }

    // ===== 通用方法（委托给 set*） =====

    override fun registerAuthenticator(authenticator: neton.core.interfaces.Authenticator) {
        setDefaultAuthenticator(authenticator)
        logger?.info("security.authenticator.custom", mapOf("name" to authenticator.name))
    }

    override fun registerAuthenticator(name: String, authenticator: neton.core.interfaces.Authenticator) {
        setGroupAuthenticator(name, authenticator)
        logger?.info("security.authenticator.named", mapOf("name" to name))
    }

    override fun bindGuard(guard: neton.core.interfaces.Guard) {
        setDefaultGuard(guard)
        logger?.info("security.guard.custom", mapOf("name" to guard.name))
    }

    override fun bindGuard(name: String, guard: neton.core.interfaces.Guard) {
        setGroupGuard(name, guard)
        logger?.info("security.guard.named", mapOf("name" to name))
    }

    private fun allAuthenticators(): List<neton.core.interfaces.Authenticator> {
        val list = mutableListOf<neton.core.interfaces.Authenticator>()
        defaultAuthenticator?.let { list.add(it) }
        list.addAll(authenticatorsByGroup.values)
        return list
    }

    private fun allGuards(): List<neton.core.interfaces.Guard> {
        val list = mutableListOf<neton.core.interfaces.Guard>()
        defaultGuard?.let { list.add(it) }
        list.addAll(guardsByGroup.values)
        return list
    }

    override fun build(): SecurityConfiguration {
        // CONTRACT — Security pipeline execution order (must not be reordered):
        //   1. Authenticator  → resolves Identity from request (JWT / Basic / custom)
        //   2. Guard          → decides access based on Identity (RequireAuth / Permission)
        //   3. RateLimit      → may use Identity for USER-scoped limiting (runs after auth)
        // Authenticator sets Identity; Guard and RateLimit depend on it.
        logger?.info("security.build")
        val auths = allAuthenticators()
        val gs = allGuards()
        return SecurityConfiguration(
            isEnabled = auths.isNotEmpty() || gs.isNotEmpty(),
            authenticatorCount = auths.size,
            guardCount = gs.size,
            authenticationContext = AuthenticationContextImpl(auths, gs),
            defaultAuthenticator = defaultAuthenticator,
            defaultGuard = defaultGuard,
            getAuthenticatorByGroup = { g ->
                if (g == null || g == "default") defaultAuthenticator else authenticatorsByGroup[g]
                    ?: defaultAuthenticator
            },
            getGuardByGroup = { g ->
                if (g == null || g == "default") defaultGuard else guardsByGroup[g] ?: defaultGuard
            },
            permissionEvaluator = permissionEvaluator
        )
    }

    override fun getAuthenticationContext(): AuthenticationContext {
        return AuthenticationContextImpl(allAuthenticators(), allGuards())
    }
}

/**
 * 认证上下文实现
 */
class AuthenticationContextImpl(
    private val authenticators: List<neton.core.interfaces.Authenticator>,
    private val guards: List<neton.core.interfaces.Guard>
) : AuthenticationContext {

    override fun currentUser(): Any? {
        return null
    }
}

// ===== 实现 Core 接口的具体类 =====

/**
 * 实现 Core 接口的 Mock 认证器
 */
class MockAuthenticatorAdapter(
    private val mockUserId: String,
    private val mockRoles: Set<String> = emptySet(),
    private val mockPermissions: Set<String> = emptySet()
) : neton.core.interfaces.Authenticator {
    override val name = "mock"

    override suspend fun authenticate(context: neton.core.interfaces.RequestContext): Identity {
        return object : Identity {
            override val id: String = mockUserId
            override val roles: Set<String> = mockRoles
            override val permissions: Set<String> = mockPermissions
        }
    }
}

/**
 * 实现 Core 接口的 JWT 认证器，委托给 [neton.security.jwt.JwtAuthenticator]。
 */
class JwtAuthenticatorAdapter(
    secretKey: String,
    private val headerName: String = "Authorization",
    private val tokenPrefix: String = "Bearer "
) : neton.core.interfaces.Authenticator {
    override val name = "jwt"

    private val delegate = neton.security.jwt.JwtAuthenticator(secretKey, headerName, tokenPrefix)

    override suspend fun authenticate(context: neton.core.interfaces.RequestContext): Identity? {
        val securityContext = object : neton.security.RequestContext {
            override val path = context.path
            override val method = context.method
            override val headers = context.headers
            override val routeGroup = context.routeGroup
            override fun getQueryParameter(name: String): String? = null
            override fun getQueryParameters(): Map<String, List<String>> = emptyMap()
            override suspend fun getBodyAsString(): String? = null
            override fun getSessionId(): String? = null
            override fun getRemoteAddress(): String? = null
        }
        return try {
            val identity = delegate.authenticate(securityContext) ?: return null
            identity
        } catch (e: neton.security.identity.AuthenticationException) {
            null
        }
    }
}

/**
 * 实现 Core 接口的 Basic 认证器。
 * 解析 "Authorization: Basic <base64(user:pass)>"，调用 [userProvider] 验证。
 */
@OptIn(ExperimentalEncodingApi::class)
class BasicAuthenticatorAdapter(
    private val userProvider: suspend (username: String, password: String) -> Identity?
) : neton.core.interfaces.Authenticator {
    override val name = "basic"

    override suspend fun authenticate(context: neton.core.interfaces.RequestContext): Identity? {
        val authHeader = context.headers["Authorization"] ?: return null
        // RFC 7617: auth-scheme comparison is case-insensitive
        if (!authHeader.startsWith("Basic ", ignoreCase = true)) return null

        val decoded = try {
            Base64.decode(authHeader.substring(6).trim()).decodeToString()
        } catch (_: Exception) {
            return null
        }
        val colon = decoded.indexOf(':')
        if (colon < 0) return null
        val username = decoded.substring(0, colon)
        val password = decoded.substring(colon + 1)
        return userProvider(username, password)
    }
}

/**
 * 实现 Core 接口的默认守卫
 */
class DefaultGuardImpl : neton.core.interfaces.Guard {
    override val name = "default"

    override suspend fun checkPermission(identity: Identity?, context: neton.core.interfaces.RequestContext): Boolean {
        return identity != null
    }
}

/**
 * 实现 Core 接口的管理员守卫
 */
class AdminGuardImpl : neton.core.interfaces.Guard {
    override val name = "admin"

    override suspend fun checkPermission(identity: Identity?, context: neton.core.interfaces.RequestContext): Boolean {
        return identity?.hasRole("admin") == true
    }
}

/**
 * 实现 Core 接口的角色守卫
 */
class RoleGuardImpl(vararg allowedRoles: String) : neton.core.interfaces.Guard {
    private val roles = allowedRoles.toList()
    override val name = "role"

    override suspend fun checkPermission(identity: Identity?, context: neton.core.interfaces.RequestContext): Boolean {
        return identity?.hasAnyRole(*roles.toTypedArray()) == true
    }
}

/**
 * 实现 Core 接口的匿名守卫
 */
class AnonymousGuardImpl : neton.core.interfaces.Guard {
    override val name = "anonymous"

    override suspend fun checkPermission(identity: Identity?, context: neton.core.interfaces.RequestContext): Boolean {
        return true
    }
}
