package neton.core.annotations

/**
 * 角色限制注解
 * 只有具有指定角色的用户才能访问
 */
annotation class RolesAllowed(vararg val roles: String)

/**
 * 需要认证注解
 * 明确标记需要认证的方法或控制器
 */
annotation class RequireAuth

/**
 * 认证主体注入注解
 * 用于在控制器方法参数中直接注入当前认证用户
 * 
 * @param required 是否必需认证用户，默认为 true
 *                 - true: 如果用户未认证，抛出异常
 *                 - false: 如果用户未认证，参数值为 null
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class AuthenticationPrincipal(val required: Boolean = true) 