package neton.http

import neton.core.http.HttpContext
import neton.core.http.HttpException
import neton.core.http.HttpStatus
import neton.core.interfaces.RequestContext
import neton.core.interfaces.RouteDefinition
import neton.core.interfaces.SecurityConfiguration
import neton.core.security.AllowAllGuard
import neton.core.security.RequirePrincipalGuard

/**
 * 安全预处理逻辑（可测入口）
 * v1.1 冻结：Mode A/B、Guard 选择策略、fail-fast 语义
 */
internal suspend fun runSecurityPreHandle(
    route: RouteDefinition,
    httpContext: HttpContext,
    requestContext: RequestContext,
    securityConfig: SecurityConfiguration?
) {
    val allowAnonymous = route.allowAnonymous
    val requireAuth = route.requireAuth

    if (allowAnonymous) {
        httpContext.removeAttribute("principal")
        return
    }

    if (securityConfig == null || !securityConfig.isEnabled) {
        if (requireAuth) {
            throw HttpException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Configuration error: @RequireAuth requires SecurityComponent. Add: install(SecurityComponent()) { }"
            )
        }
        httpContext.removeAttribute("principal")
        return
    }

    val authenticator = securityConfig.getAuthenticatorByGroup?.invoke(requestContext.routeGroup)
        ?: securityConfig.defaultAuthenticator
    if (requireAuth && authenticator == null) {
        throw HttpException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Configuration error: @RequireAuth requires at least one Authenticator. Add: security { registerJwtAuthenticator(...) } or registerBasicAuthenticator(...)"
            )
    }

    val principal = if (authenticator != null) {
        authenticator.authenticate(requestContext)
    } else {
        null
    }

    if (principal == null && requireAuth) {
        throw HttpException(HttpStatus.UNAUTHORIZED, "Authentication required")
    }

    if (principal != null) {
        httpContext.setAttribute("principal", principal)
    } else {
        httpContext.removeAttribute("principal")
    }

    val guard = if (requireAuth) {
        securityConfig.getGuardByGroup?.invoke(requestContext.routeGroup)
            ?: securityConfig.defaultGuard
            ?: RequirePrincipalGuard()
    } else {
        AllowAllGuard()
    }

    val allowed = guard.checkPermission(principal, requestContext)
    if (!allowed) {
        throw HttpException(HttpStatus.FORBIDDEN, "Access denied")
    }
}
