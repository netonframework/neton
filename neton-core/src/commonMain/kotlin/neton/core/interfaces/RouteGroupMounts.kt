package neton.core.interfaces

/**
 * 路由组 → mount 路径映射
 * 由 RoutingComponent 从 routing.conf 解析并绑定到 ctx，
 * 供 adapter 在注册路由树时按 group 包一层 route(mount)，pattern 保持原样。
 *
 * 冻结语义（v1.1）：
 * - mount 不改 pattern 字符串
 * - adapter 在注册时按 group 包一层 route(mount)
 */
data class RouteGroupMounts(val groupToMount: Map<String, String>)
