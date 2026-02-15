package neton.routing

import neton.core.Neton
import neton.core.component.NetonComponent
import neton.core.component.NetonContext
import neton.core.interfaces.RequestEngine
import neton.core.interfaces.RouteGroupSecurityConfig
import neton.core.interfaces.RouteGroupSecurityConfigs
import neton.core.config.ConfigLoader
import neton.logging.Logger
import neton.logging.LoggerFactory
import neton.routing.engine.DefaultRequestEngine

/** 模块内 Logger 注入点，由 RoutingComponent.init 设置 */
internal object RoutingLog {
    var log: Logger? = null
}

/**
 * Routing 组件 - 单实体，直接实现 NetonComponent（无 wrapper）
 */
object RoutingComponent : NetonComponent<RequestEngine> {

    override fun defaultConfig(): RequestEngine = RoutingRequestEngineAdapter(DefaultRequestEngine())

    override suspend fun init(ctx: NetonContext, config: RequestEngine) {
        RoutingLog.log = ctx.getOrNull(LoggerFactory::class)?.get("neton.routing")
        val log = RoutingLog.log
        val rawConfig = ConfigLoader.loadModuleConfig(
            "routing",
            configPath = "config",
            environment = ConfigLoader.resolveEnvironment(ctx.args),
            args = ctx.args
        )
        val routingConfig = parseRoutingConfig(rawConfig)
        if (routingConfig != null) {
            val configuredGroups =
                neton.core.interfaces.ConfiguredRouteGroups(routingConfig.groups.map { it.name }.toSet())
            ctx.bind(configuredGroups)
            val groupToMount = routingConfig.groups
                .filter { it.mount.type == RouteMountType.PATH }
                .associate { it.name to it.mount.value }
            ctx.bind(neton.core.interfaces.RouteGroupMounts(groupToMount))
            val securityConfigs = RouteGroupSecurityConfigs(
                routingConfig.groups.associate { g ->
                    g.name to RouteGroupSecurityConfig(
                        requireAuth = g.requireAuth,
                        allowAnonymous = g.allowAnonymous.toSet()
                    )
                }
            )
            ctx.bind(securityConfigs)
        } else {
            ctx.bind(neton.core.interfaces.ConfiguredRouteGroups(emptySet()))
            ctx.bind(neton.core.interfaces.RouteGroupMounts(emptyMap()))
        }
        ctx.bind(RequestEngine::class, config)
        (config as? RoutingRequestEngineAdapter)?.setLogger(log)
    }

    override suspend fun start(ctx: NetonContext) {
        try {
            neton.core.generated.GeneratedInitializer.initialize(ctx)
        } catch (_: Exception) {
            ControllerScanner.registerAllControllers()
        }
    }

    /**
     * 文件名 = 命名空间：routing.conf → config.routing.*
     * 冻结：routing.conf 根级平铺（debug/groups），禁止 [routing]。
     */
    private fun parseRoutingConfig(rawConfig: Map<String, Any?>?): RoutingConfig? {
        if (rawConfig == null) return null
        try {
            val debug = rawConfig["debug"] as? Boolean ?: false

            @Suppress("UNCHECKED_CAST")
            val groupsData = (rawConfig["groups"] as? List<*>) ?: emptyList<Map<*, *>>()
            val routeGroups = groupsData.mapNotNull { item ->
                val groupData = item as? Map<*, *> ?: return@mapNotNull null
                val name = groupData["name"] as? String ?: return@mapNotNull null
                val mount = groupData["mount"] as? String ?: return@mapNotNull null
                val requireAuth = groupData["requireAuth"] as? Boolean ?: false

                @Suppress("UNCHECKED_CAST")
                val allowAnonymous =
                    (groupData["allowAnonymous"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
                RouteGroup(name, RouteMountConfig(RouteMountType.PATH, mount), requireAuth, allowAnonymous)
            }
            return RoutingConfig(debug = debug, groups = routeGroups)
        } catch (_: Exception) {
            return null
        }
    }
}

fun Neton.LaunchBuilder.routing(block: RequestEngine.() -> Unit) = install(RoutingComponent, block)
