package neton.core.interfaces

/**
 * 单个路由组的安全配置（来自 routing.conf）
 */
data class RouteGroupSecurityConfig(
    val requireAuth: Boolean,
    val allowAnonymous: Set<String>   // 精确 match 白名单（route pattern）
)

/**
 * 所有路由组的安全配置映射
 */
data class RouteGroupSecurityConfigs(
    val configs: Map<String, RouteGroupSecurityConfig>
)
