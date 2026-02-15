package neton.http

import neton.core.http.HttpContext
import neton.core.http.HttpException
import neton.core.http.HttpStatus
import neton.core.interfaces.RequestContext
import neton.core.interfaces.RouteDefinition
import neton.core.interfaces.RouteGroupSecurityConfigs
import neton.core.interfaces.SecurityAttributes
import neton.core.interfaces.SecurityConfiguration
import neton.core.security.AllowAllGuard
import neton.core.security.RequireIdentityGuard

/**
 * 安全预处理逻辑（可测入口）
 * v1.2：Identity 体系 + @Permission + PermissionEvaluator + 路由组白名单
 *
 * 优先级：@AllowAnonymous > whitelist > group.requireAuth
 */
internal suspend fun runSecurityPreHandle(
    route: RouteDefinition,
    httpContext: HttpContext,
    requestContext: RequestContext,
    securityConfig: SecurityConfiguration?,
    routeGroupSecurityConfigs: RouteGroupSecurityConfigs? = null
) {
    // 1. 计算 isAnonymousAllowed
    val groupConfig = requestContext.routeGroup?.let { routeGroupSecurityConfigs?.configs?.get(it) }
    val isAnonymousAllowed = route.allowAnonymous
            || (groupConfig != null && route.pattern in groupConfig.allowAnonymous)
            || (groupConfig != null && !groupConfig.requireAuth && !route.requireAuth)

    if (isAnonymousAllowed) {
        httpContext.removeAttribute(SecurityAttributes.IDENTITY)
        return
    }

    // 2. 安全未配置时的 fail-fast
    val requireAuth = route.requireAuth || (groupConfig?.requireAuth == true)

    if (securityConfig == null || !securityConfig.isEnabled) {
        if (requireAuth) {
            throw HttpException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Configuration error: @RequireAuth requires SecurityComponent. Add: install(SecurityComponent()) { }"
            )
        }
        httpContext.removeAttribute(SecurityAttributes.IDENTITY)
        return
    }

    // 3. 认证
    val authenticator = securityConfig.getAuthenticatorByGroup?.invoke(requestContext.routeGroup)
        ?: securityConfig.defaultAuthenticator
    if (requireAuth && authenticator == null) {
        throw HttpException(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "Configuration error: @RequireAuth requires at least one Authenticator. Add: security { registerJwtAuthenticator(...) } or registerBasicAuthenticator(...)"
        )
    }

    val identity = if (authenticator != null) {
        authenticator.authenticate(requestContext)
    } else {
        null
    }

    if (identity == null && requireAuth) {
        throw HttpException(HttpStatus.UNAUTHORIZED, "Authentication required")
    }

    // 4. 存储 identity
    if (identity != null) {
        httpContext.setAttribute(SecurityAttributes.IDENTITY, identity)
    } else {
        httpContext.removeAttribute(SecurityAttributes.IDENTITY)
    }

    // 5. 权限检查（仅当 route.permission != null）
    val permission = route.permission
    if (permission != null && identity != null) {
        val evaluator = securityConfig.permissionEvaluator
        val allowed = if (evaluator != null) {
            evaluator.allowed(identity, permission, requestContext)
        } else {
            identity.hasPermission(permission)
        }
        if (!allowed) {
            throw HttpException(HttpStatus.FORBIDDEN, "Permission denied: $permission")
        }
    } else if (permission != null && identity == null) {
        throw HttpException(HttpStatus.UNAUTHORIZED, "Authentication required")
    }

    // 6. Guard 检查
    val guard = if (requireAuth) {
        securityConfig.getGuardByGroup?.invoke(requestContext.routeGroup)
            ?: securityConfig.defaultGuard
            ?: RequireIdentityGuard()
    } else {
        AllowAllGuard()
    }

    val allowed = guard.checkPermission(identity, requestContext)
    if (!allowed) {
        throw HttpException(HttpStatus.FORBIDDEN, "Access denied")
    }
}
