package neton.core.interfaces

/**
 * 已配置的路由组名称集合
 * 由 RoutingComponent 在 init 时从 routing.conf 解析并绑定到 ctx，
 * 供安全管道推断 routeGroup 时过滤：仅当包最后段命中此集合才视为有效 routeGroup。
 */
data class ConfiguredRouteGroups(val names: Set<String>)
