package neton.core.security

import neton.core.interfaces.Guard
import neton.core.interfaces.Identity
import neton.core.interfaces.RequestContext

/**
 * 安全未启用时的认证上下文：始终返回 null（匿名）。
 */
class DisabledAuthenticationContext : AuthenticationContext {
    override fun currentUser(): Any? = null
}

/**
 * 允许所有请求的守卫（@AllowAnonymous 或未配置守卫时使用）。
 */
class AllowAllGuard : Guard {
    override val name = "allow-all"
    override suspend fun checkPermission(identity: Identity?, context: RequestContext): Boolean = true
}

/**
 * 要求已认证的守卫（@RequireAuth 且未配置守卫时使用）：identity 非空则允许。
 */
class RequireIdentityGuard : Guard {
    override val name = "require-identity"
    override suspend fun checkPermission(identity: Identity?, context: RequestContext): Boolean = identity != null
}
