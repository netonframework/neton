package neton.security

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
 * 真正的安全构建器实现 - 实现 Core 模块接口
 */
class RealSecurityBuilder : neton.core.interfaces.SecurityBuilder {

    private var logger: neton.logging.Logger? = null
    fun setLogger(log: neton.logging.Logger?) {
        logger = log
    }

    private var defaultAuthenticator: neton.core.interfaces.Authenticator? = null
    private var defaultGuard: neton.core.interfaces.Guard? = null
    private val authenticatorsByGroup = mutableMapOf<String, neton.core.interfaces.Authenticator>()
    private val guardsByGroup = mutableMapOf<String, neton.core.interfaces.Guard>()
    private var permissionEvaluator: PermissionEvaluator? = null

    private fun validateGroupName(group: String) {
        if (group.isBlank()) {
            throw IllegalArgumentException("Security group name must not be blank")
        }
    }

    override fun setDefaultAuthenticator(auth: neton.core.interfaces.Authenticator?) {
        defaultAuthenticator = auth
        logger?.info("security.set.default.authenticator", mapOf("name" to (auth?.name ?: "null")))
    }

    override fun setDefaultGuard(guard: neton.core.interfaces.Guard) {
        defaultGuard = guard
        logger?.info("security.set.default.guard", mapOf("name" to guard.name))
    }

    override fun setGroupAuthenticator(group: String, auth: neton.core.interfaces.Authenticator?) {
        validateGroupName(group)
        if (authenticatorsByGroup.containsKey(group)) {
            logger?.warn("security.group.overwrite", mapOf("group" to group, "field" to "authenticator"))
        }
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
        val authenticator = RealMockAuthenticator(userId, roles, permissions)
        setDefaultAuthenticator(authenticator)
        logger?.info("security.authenticator.mock", mapOf("userId" to userId))
    }

    override fun registerMockAuthenticator(name: String, userId: String, roles: Set<String>, permissions: Set<String>) {
        validateGroupName(name)
        val authenticator = RealMockAuthenticator(userId, roles, permissions)
        setGroupAuthenticator(name, authenticator)
        logger?.info("security.authenticator.mock.named", mapOf("name" to name))
    }

    override fun registerJwtAuthenticator(secretKey: String, headerName: String, tokenPrefix: String) {
        val authenticator = RealJwtAuthenticator(secretKey, headerName, tokenPrefix)
        setDefaultAuthenticator(authenticator)
        logger?.info("security.authenticator.jwt")
    }

    override fun registerSessionAuthenticator(sessionKey: String) {
        val authenticator = RealSessionAuthenticator(sessionKey)
        setDefaultAuthenticator(authenticator)
        logger?.info("security.authenticator.session")
    }

    override fun registerBasicAuthenticator(userProvider: suspend (username: String, password: String) -> Identity?) {
        val authenticator = RealBasicAuthenticator(userProvider)
        setDefaultAuthenticator(authenticator)
        logger?.info("security.authenticator.basic")
    }

    // ===== 守卫配置方法 =====

    override fun bindDefaultGuard() {
        setDefaultGuard(RealDefaultGuard())
        logger?.info("security.guard.default")
    }

    override fun bindAdminGuard() {
        setDefaultGuard(RealAdminGuard())
        logger?.info("security.guard.admin")
    }

    override fun bindRoleGuard(vararg roles: String) {
        setDefaultGuard(RealRoleGuard(*roles))
        logger?.info("security.guard.role", mapOf("roles" to roles.toList()))
    }

    override fun bindNamedRoleGuard(name: String, vararg roles: String) {
        val guard = RealRoleGuard(*roles)
        setGroupGuard(name, guard)
        logger?.info("security.guard.named", mapOf("name" to name))
    }

    override fun bindAnonymousGuard() {
        setDefaultGuard(RealAnonymousGuard())
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
        logger?.info("security.build")
        val auths = allAuthenticators()
        val gs = allGuards()
        return SecurityConfiguration(
            isEnabled = auths.isNotEmpty() || gs.isNotEmpty(),
            authenticatorCount = auths.size,
            guardCount = gs.size,
            authenticationContext = RealAuthenticationContext(auths, gs),
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
        return RealAuthenticationContext(allAuthenticators(), allGuards())
    }
}

/**
 * 真正的认证上下文实现
 */
class RealAuthenticationContext(
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
class RealMockAuthenticator(
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
 * 实现 Core 接口的 JWT 认证器
 */
class RealJwtAuthenticator(
    private val secretKey: String,
    private val headerName: String = "Authorization",
    private val tokenPrefix: String = "Bearer "
) : neton.core.interfaces.Authenticator {
    override val name = "jwt"

    override suspend fun authenticate(context: neton.core.interfaces.RequestContext): Identity? {
        val authHeader = context.headers[headerName] ?: return null
        val token = if (authHeader.startsWith(tokenPrefix)) {
            authHeader.substring(tokenPrefix.length)
        } else {
            authHeader
        }

        // TODO: 实现 JWT 验证逻辑（delegate to JwtAuthenticatorV1）
        return null
    }
}

/**
 * 实现 Core 接口的会话认证器
 */
class RealSessionAuthenticator(
    private val sessionKey: String = "user_id"
) : neton.core.interfaces.Authenticator {
    override val name = "session"

    override suspend fun authenticate(context: neton.core.interfaces.RequestContext): Identity? {
        // TODO: 实现会话认证逻辑
        return null
    }
}

/**
 * 实现 Core 接口的 Basic 认证器
 */
class RealBasicAuthenticator(
    private val userProvider: suspend (username: String, password: String) -> Identity?
) : neton.core.interfaces.Authenticator {
    override val name = "basic"

    override suspend fun authenticate(context: neton.core.interfaces.RequestContext): Identity? {
        val authHeader = context.headers["Authorization"] ?: return null
        if (!authHeader.startsWith("Basic ")) return null

        try {
            // TODO: 实现 Base64 解码和认证逻辑
            return null
        } catch (e: Exception) {
            return null
        }
    }
}

/**
 * 实现 Core 接口的默认守卫
 */
class RealDefaultGuard : neton.core.interfaces.Guard {
    override val name = "default"

    override suspend fun checkPermission(identity: Identity?, context: neton.core.interfaces.RequestContext): Boolean {
        return identity != null
    }
}

/**
 * 实现 Core 接口的管理员守卫
 */
class RealAdminGuard : neton.core.interfaces.Guard {
    override val name = "admin"

    override suspend fun checkPermission(identity: Identity?, context: neton.core.interfaces.RequestContext): Boolean {
        return identity?.hasRole("admin") == true
    }
}

/**
 * 实现 Core 接口的角色守卫
 */
class RealRoleGuard(vararg allowedRoles: String) : neton.core.interfaces.Guard {
    private val roles = allowedRoles.toList()
    override val name = "role"

    override suspend fun checkPermission(identity: Identity?, context: neton.core.interfaces.RequestContext): Boolean {
        return identity?.hasAnyRole(*roles.toTypedArray()) == true
    }
}

/**
 * 实现 Core 接口的匿名守卫
 */
class RealAnonymousGuard : neton.core.interfaces.Guard {
    override val name = "anonymous"

    override suspend fun checkPermission(identity: Identity?, context: neton.core.interfaces.RequestContext): Boolean {
        return true
    }
}
