package neton.core.interfaces

import neton.core.security.AuthenticationContext

/**
 * 路由组安全配置 - 用于启动期审计/日志
 */
data class SecurityGroupConfig(
    val group: String?,
    val authenticator: String?,
    val guard: String,
    /** 注册该组认证器的来源（配置器全限定名或 DSL），未注册时为 null。 */
    val authenticatorSource: String? = null,
    /** 注册该组守卫的来源，未注册时为 null。 */
    val guardSource: String? = null,
)

/**
 * 安全构建器接口 - Core 模块定义的标准接口
 *
 * 使用工厂模式，用户通过方法配置安全组件，而不直接依赖具体实现
 *
 * # 注册槽位唯一（不允许按装配顺序静默覆盖）
 *
 * 默认认证器、每个路由组的认证器、默认守卫、每个路由组的守卫、权限评估器各是一个槽位。
 * 一个槽位只能被注册一次：第二次 `set*` / `register*` / `bind*` 在启动期抛
 * `security.registration.conflict`，消息里同时给出已有与新来的来源。要替换别人注册的实现，
 * 必须用 `replace*` 显式声明被替换的来源（配置器全限定名，见 [neton.core.config.NetonConfigurer.source]），
 * 且替换方必须在被替换方之后执行（更大的 order）。
 */
interface SecurityBuilder {

    // ===== 认证器配置方法 =====

    /**
     * 注册模拟认证器 - 开发测试使用
     */
    fun registerMockAuthenticator(
        userId: String,
        roles: Set<String> = emptySet(),
        permissions: Set<String> = emptySet()
    )

    /**
     * 注册命名模拟认证器
     */
    fun registerMockAuthenticator(
        name: String,
        userId: String,
        roles: Set<String> = emptySet(),
        permissions: Set<String> = emptySet()
    )

    /**
     * 注册 JWT 认证器
     */
    fun registerJwtAuthenticator(
        secretKey: String,
        headerName: String = "Authorization",
        tokenPrefix: String = "Bearer "
    )

    /**
     * 注册会话认证器
     */
    fun registerSessionAuthenticator(sessionKey: String = "user_id")

    /**
     * 注册 Basic 认证器
     */
    fun registerBasicAuthenticator(userProvider: suspend (username: String, password: String) -> Identity?)

    // ===== 守卫配置方法 =====

    /**
     * 绑定默认守卫 - 允许所有已认证用户
     */
    fun bindDefaultGuard()

    /**
     * 绑定管理员守卫 - 只允许管理员角色
     */
    fun bindAdminGuard()

    /**
     * 绑定角色守卫 - 允许指定角色
     */
    fun bindRoleGuard(vararg roles: String)

    /**
     * 绑定命名角色守卫
     */
    fun bindNamedRoleGuard(name: String, vararg roles: String)

    /**
     * 绑定匿名守卫 - 允许匿名访问
     */
    fun bindAnonymousGuard()

    // ===== 组配置 API（推荐：配置类直接调用，调试友好） =====

    /**
     * 设置默认组认证器（routeGroup = null）
     */
    fun setDefaultAuthenticator(auth: Authenticator?)

    /**
     * 设置默认组守卫（routeGroup = null）
     */
    fun setDefaultGuard(guard: Guard)

    /**
     * 设置指定组的认证器
     * @throws IllegalArgumentException 当 group 为空或非法时 fail-fast
     */
    fun setGroupAuthenticator(group: String, auth: Authenticator?)

    /**
     * 设置指定组的守卫
     * @throws IllegalArgumentException 当 group 为空或非法时 fail-fast
     */
    fun setGroupGuard(group: String, guard: Guard)

    // ===== 显式覆盖（唯一允许替换已占用槽位的入口） =====

    /**
     * 替换默认认证器。[replaces] 必须等于当前占用该槽位的来源，否则启动失败。
     */
    fun replaceDefaultAuthenticator(replaces: String, auth: Authenticator)

    /**
     * 替换指定组的认证器。[replaces] 必须等于当前占用该槽位的来源，否则启动失败。
     */
    fun replaceGroupAuthenticator(group: String, replaces: String, auth: Authenticator)

    /**
     * 替换默认守卫。[replaces] 必须等于当前占用该槽位的来源，否则启动失败。
     */
    fun replaceDefaultGuard(replaces: String, guard: Guard)

    /**
     * 替换指定组的守卫。[replaces] 必须等于当前占用该槽位的来源，否则启动失败。
     */
    fun replaceGroupGuard(group: String, replaces: String, guard: Guard)

    /**
     * 替换权限评估器。[replaces] 必须等于当前占用该槽位的来源，否则启动失败。
     */
    fun replacePermissionEvaluator(replaces: String, evaluator: PermissionEvaluator)

    // ===== 通用方法（与 set* 等效，保留兼容） =====

    /**
     * 注册自定义认证器（默认组），等价于 setDefaultAuthenticator
     */
    fun registerAuthenticator(authenticator: Authenticator)

    /**
     * 注册命名自定义认证器（指定组），等价于 setGroupAuthenticator
     */
    fun registerAuthenticator(name: String, authenticator: Authenticator)

    /**
     * 绑定自定义守卫（默认组），等价于 setDefaultGuard
     */
    fun bindGuard(guard: Guard)

    /**
     * 绑定命名自定义守卫（指定组），等价于 setGroupGuard
     */
    fun bindGuard(name: String, guard: Guard)

    /**
     * 构建安全配置
     */
    fun build(): SecurityConfiguration

    /**
     * 获取认证上下文
     */
    fun getAuthenticationContext(): AuthenticationContext

    /**
     * 设置权限评估器（业务可替换，实现 superadmin 等自定义逻辑）
     */
    fun setPermissionEvaluator(evaluator: PermissionEvaluator)

    /**
     * 获取当前组配置（用于启动期审计日志）
     */
    fun getGroupConfigSummary(): List<SecurityGroupConfig>
}

/**
 * 安全配置 - Core 模块标准定义
 * @param getAuthenticatorByGroup 按 routeGroup 解析认证器；null 或返回 null 时使用 defaultAuthenticator
 * @param getGuardByGroup 按 routeGroup 解析守卫；null 或返回 null 时使用 defaultGuard
 */
data class SecurityConfiguration(
    val isEnabled: Boolean,
    val authenticatorCount: Int,
    val guardCount: Int,
    val authenticationContext: AuthenticationContext,
    val defaultAuthenticator: Authenticator? = null,
    val defaultGuard: Guard? = null,
    val getAuthenticatorByGroup: ((String?) -> Authenticator?)? = null,
    val getGuardByGroup: ((String?) -> Guard?)? = null,
    val permissionEvaluator: PermissionEvaluator? = null
)
