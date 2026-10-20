package neton.security

import neton.core.Neton
import neton.core.component.NetonComponent
import neton.core.component.NetonContext
import neton.core.config.NetonConfigRegistry
import neton.core.interfaces.SecurityBuilder
import neton.logging.Logger
import neton.logging.LoggerFactory

/** 模块内 Logger 注入点，由 SecurityComponent.init 设置 */
internal object SecurityLog {
    var log: Logger? = null
}

/**
 * Security 组件 - 无内部状态，绑定到 ctx
 */
object SecurityComponent : NetonComponent<SecurityBuilder> {

    override fun defaultConfig(): SecurityBuilder = SecurityBuilderImpl()

    override suspend fun init(ctx: NetonContext, config: SecurityBuilder) {
        val log = ctx.getOrNull(LoggerFactory::class)?.get("neton.security")
        SecurityLog.log = log
        log?.info("security.init")
        ctx.bind(SecurityBuilder::class, config)
        (config as? SecurityBuilderImpl)?.setLogger(log)
        log?.info("security.initialized")
    }

    override suspend fun configure(ctx: NetonContext) {
        val config = ctx.get<SecurityBuilder>()
        val impl = config as? SecurityBuilderImpl
        ctx.getOrNull(NetonConfigRegistry::class)?.securityConfigurers
            ?.sortedBy { it.order }
            ?.forEach { configurer ->
                if (impl != null) impl.withSource(configurer.source, configurer.order) { configurer.configure(ctx, config) }
                else configurer.configure(ctx, config)
            }
        val log = ctx.getOrNull(LoggerFactory::class)?.get("neton.security")
        // One line per route group: which authenticator/guard serves it and which configurer registered it.
        config.getGroupConfigSummary().forEach { entry ->
            log?.info(
                "security.group.config", mapOf(
                    "group" to (entry.group ?: "default"),
                    "authenticator" to (entry.authenticator ?: "<none>"),
                    "authenticatorSource" to (entry.authenticatorSource ?: "<none>"),
                    "guard" to entry.guard,
                    "guardSource" to (entry.guardSource ?: "<none>"),
                )
            )
        }
    }
}

fun Neton.LaunchBuilder.security(block: SecurityBuilder.() -> Unit) = install(SecurityComponent, block)
