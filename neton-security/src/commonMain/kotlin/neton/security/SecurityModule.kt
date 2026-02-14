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
 * 真正的安全工厂实现
 */
class RealSecurityFactory : neton.core.interfaces.SecurityFactory {
    
    override fun createAuthenticator(type: String, config: Map<String, Any>): neton.core.interfaces.Authenticator {
        return when (type) {
            "mock" -> RealMockAuthenticator(
                mockUser = createPrincipal(
                    config["userId"] as? String ?: "mock-user",
                    config["roles"] as? List<String> ?: listOf("user"),
                    config["attributes"] as? Map<String, Any> ?: mapOf()
                )
            )
            "jwt" -> RealJwtAuthenticator(
                config["secretKey"] as? String ?: throw IllegalArgumentException("JWT secretKey is required"),
                config["headerName"] as? String ?: "Authorization",
                config["tokenPrefix"] as? String ?: "Bearer "
            )
            "session" -> RealSessionAuthenticator(
                config["sessionKey"] as? String ?: "user_id"
            )
            "basic" -> {
                val userProvider = config["userProvider"] as? (suspend (String, String) -> neton.core.interfaces.Principal?)
                    ?: throw IllegalArgumentException("Basic auth userProvider is required")
                RealBasicAuthenticator(userProvider)
            }
            else -> throw IllegalArgumentException("Unknown authenticator type: $type")
        }
    }
    
    override fun createGuard(type: String, config: Map<String, Any>): neton.core.interfaces.Guard {
        return when (type) {
            "default" -> RealDefaultGuard()
            "admin" -> RealAdminGuard()
                         "role" -> {
                val roles = config["roles"] as? Array<String> 
                    ?: throw IllegalArgumentException("Role guard requires roles config")
                RealRoleGuard(*roles)
            }
            "anonymous" -> RealAnonymousGuard()
            else -> throw IllegalArgumentException("Unknown guard type: $type")
        }
    }
    
    override fun createPrincipal(id: String, roles: List<String>, attributes: Map<String, Any>): neton.core.interfaces.Principal {
        return object : neton.core.interfaces.Principal {
            override val id: String = id
            override val roles: List<String> = roles
            override val attributes: Map<String, Any> = attributes
        }
    }
}

/**
 * 真正的安全构建器实现 - 实现 Core 模块接口
 */
class RealSecurityBuilder : neton.core.interfaces.SecurityBuilder {

    private var logger: neton.logging.Logger? = null
    fun setLogger(log: neton.logging.Logger?) { logger = log }

    private var defaultAuthenticator: neton.core.interfaces.Authenticator? = null
    private var defaultGuard: neton.core.interfaces.Guard? = null
    private val authenticatorsByGroup = mutableMapOf<String, neton.core.interfaces.Authenticator>()
    private val guardsByGroup = mutableMapOf<String, neton.core.interfaces.Guard>()
    private val securityFactory = RealSecurityFactory()

    private fun validateGroupName(group: String) {
        if (group.isBlank()) {
            throw IllegalArgumentException("Security group name must not be blank")
        }
    }

    override fun getSecurityFactory(): neton.core.interfaces.SecurityFactory = securityFactory

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
        list.add(SecurityGroupConfig(
            group = null,
            authenticator = defaultAuthenticator?.name,
            guard = defaultGuard?.name ?: "<none>"
        ))
        val allGroups = (authenticatorsByGroup.keys + guardsByGroup.keys).toSet().sorted()
        allGroups.forEach { g: String ->
            list.add(SecurityGroupConfig(
                group = g,
                authenticator = authenticatorsByGroup[g]?.name,
                guard = guardsByGroup[g]?.name ?: "<none>"
            ))
        }
        return list
    }

    override fun registerMockAuthenticator(userId: String, roles: List<String>, attributes: Map<String, Any>) {
        val authenticator = securityFactory.createAuthenticator("mock", mapOf(
            "userId" to userId,
            "roles" to roles,
            "attributes" to attributes
        ))
        setDefaultAuthenticator(authenticator)
        logger?.info("security.authenticator.mock", mapOf("userId" to userId))
    }

    override fun registerMockAuthenticator(name: String, userId: String, roles: List<String>, attributes: Map<String, Any>) {
        validateGroupName(name)
        val authenticator = securityFactory.createAuthenticator("mock", mapOf(
            "userId" to userId,
            "roles" to roles,
            "attributes" to attributes
        ))
        setGroupAuthenticator(name, authenticator)
        logger?.info("security.authenticator.mock.named", mapOf("name" to name))
    }
    
    override fun registerJwtAuthenticator(secretKey: String, headerName: String, tokenPrefix: String) {
        val authenticator = securityFactory.createAuthenticator("jwt", mapOf(
            "secretKey" to secretKey,
            "headerName" to headerName,
            "tokenPrefix" to tokenPrefix
        ))
        setDefaultAuthenticator(authenticator)
        logger?.info("security.authenticator.jwt")
    }
    
    override fun registerSessionAuthenticator(sessionKey: String) {
        val authenticator = securityFactory.createAuthenticator("session", mapOf(
            "sessionKey" to sessionKey
        ))
        setDefaultAuthenticator(authenticator)
        logger?.info("security.authenticator.session")
    }
    
    override fun registerBasicAuthenticator(userProvider: suspend (username: String, password: String) -> neton.core.interfaces.Principal?) {
        val authenticator = securityFactory.createAuthenticator("basic", mapOf(
            "userProvider" to userProvider
        ))
        setDefaultAuthenticator(authenticator)
        logger?.info("security.authenticator.basic")
    }
    
    // ===== 守卫配置方法 =====
    
    override fun bindDefaultGuard() {
        setDefaultGuard(securityFactory.createGuard("default", mapOf()))
        logger?.info("security.guard.default")
    }
    
    override fun bindAdminGuard() {
        setDefaultGuard(securityFactory.createGuard("admin", mapOf()))
        logger?.info("security.guard.admin")
    }
    
    override fun bindRoleGuard(vararg roles: String) {
        setDefaultGuard(securityFactory.createGuard("role", mapOf("roles" to roles)))
        logger?.info("security.guard.role", mapOf("roles" to roles.toList()))
    }
    
    override fun bindNamedRoleGuard(name: String, vararg roles: String) {
        val guard = securityFactory.createGuard("role", mapOf("roles" to roles))
        setGroupGuard(name, guard)
        logger?.info("security.guard.named", mapOf("name" to name))
    }
    
    override fun bindAnonymousGuard() {
        setDefaultGuard(securityFactory.createGuard("anonymous", mapOf()))
        logger?.info("security.guard.anonymous")
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
                if (g == null || g == "default") defaultAuthenticator else authenticatorsByGroup[g] ?: defaultAuthenticator
            },
            getGuardByGroup = { g ->
                if (g == null || g == "default") defaultGuard else guardsByGroup[g] ?: defaultGuard
            }
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
        // TODO: 实现真正的当前用户获取逻辑
        // 这里应该从请求上下文或会话中获取当前认证的用户
        return null
    }
}

// ===== 实现 Core 接口的具体类 =====

/**
 * 实现 Core 接口的 Mock 认证器
 */
class RealMockAuthenticator(
    private val mockUser: neton.core.interfaces.Principal
) : neton.core.interfaces.Authenticator {
    override val name = "mock"
    
    override suspend fun authenticate(context: neton.core.interfaces.RequestContext): neton.core.interfaces.Principal = mockUser
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
    
    override suspend fun authenticate(context: neton.core.interfaces.RequestContext): neton.core.interfaces.Principal? {
        val authHeader = context.headers[headerName] ?: return null
        val token = if (authHeader.startsWith(tokenPrefix)) {
            authHeader.substring(tokenPrefix.length)
        } else {
            authHeader
        }
        
        // TODO: 实现 JWT 验证逻辑
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
    
    override suspend fun authenticate(context: neton.core.interfaces.RequestContext): neton.core.interfaces.Principal? {
        // TODO: 实现会话认证逻辑
        return null
    }
}

/**
 * 实现 Core 接口的 Basic 认证器
 */
class RealBasicAuthenticator(
    private val userProvider: suspend (username: String, password: String) -> neton.core.interfaces.Principal?
) : neton.core.interfaces.Authenticator {
    override val name = "basic"
    
    override suspend fun authenticate(context: neton.core.interfaces.RequestContext): neton.core.interfaces.Principal? {
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
    
    override suspend fun checkPermission(principal: neton.core.interfaces.Principal?, context: neton.core.interfaces.RequestContext): Boolean {
        return principal != null // 只要已认证就允许
    }
}

/**
 * 实现 Core 接口的管理员守卫
 */
class RealAdminGuard : neton.core.interfaces.Guard {
    override val name = "admin"
    
    override suspend fun checkPermission(principal: neton.core.interfaces.Principal?, context: neton.core.interfaces.RequestContext): Boolean {
        return principal?.hasRole("admin") == true
    }
}

/**
 * 实现 Core 接口的角色守卫
 */
class RealRoleGuard(vararg allowedRoles: String) : neton.core.interfaces.Guard {
    private val roles = allowedRoles.toList()
    override val name = "role"
    
    override suspend fun checkPermission(principal: neton.core.interfaces.Principal?, context: neton.core.interfaces.RequestContext): Boolean {
        return principal?.hasAnyRole(*roles.toTypedArray()) == true
    }
}

/**
 * 实现 Core 接口的匿名守卫
 */
class RealAnonymousGuard : neton.core.interfaces.Guard {
    override val name = "anonymous"
    
    override suspend fun checkPermission(principal: neton.core.interfaces.Principal?, context: neton.core.interfaces.RequestContext): Boolean {
        return true // 总是允许
    }
} 