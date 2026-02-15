package neton.core.annotations

/**
 * 角色限制注解 — 粗粒度
 * 只有具有指定角色的用户才能访问
 */
annotation class RolesAllowed(vararg val roles: String)

/**
 * 权限限制注解 — 细粒度
 * 只在标注时触发权限检查，通过 PermissionEvaluator 评估
 * 格式：module:resource:action，如 "system:user:edit"
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class Permission(val value: String)

/**
 * 需要认证注解
 */
annotation class RequireAuth

/**
 * 当前用户注入注解
 * 用于在控制器方法参数中注入当前认证用户（Identity 或其子类型）
 *
 * @param required true: 未认证时抛异常; false: 未认证时参数为 null
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class CurrentUser(val required: Boolean = true)

/**
 * @deprecated 使用 @CurrentUser 替代
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
@Deprecated("Use @CurrentUser instead", replaceWith = ReplaceWith("CurrentUser"))
annotation class AuthenticationPrincipal(val required: Boolean = true)
