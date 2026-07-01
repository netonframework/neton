// neton-ai/src/commonMain/kotlin/neton/ai/AiComponent.kt
package neton.ai

import neton.ai.internal.AiClientFactory
import neton.ai.internal.applyFileMap
import neton.core.Neton
import neton.core.component.NetonComponent
import neton.core.component.NetonContext
import neton.core.config.ConfigLoader
import neton.http.client.NetonHttpClient
import neton.logging.LoggerFactory

/**
 * Mode 2 thin adapter — binds AiClient into NetonContext for downstream code.
 *
 * Precondition: HttpClientComponent must be installed BEFORE this component. AiComponent.init
 * fail-fasts with a clear error if NetonHttpClient is not in the context.
 *
 * Config flow per spec §4.3:
 *   1. DSL block runs (caller-provided config)
 *   2. config/ai.conf is loaded and merged (DSL explicit fields preserved)
 *   3. validate() runs; bind on success
 */
object AiComponent : NetonComponent<AiConfig> {

    override fun defaultConfig(): AiConfig = AiConfig()

    override suspend fun init(ctx: NetonContext, config: AiConfig) {
        val httpClient = ctx.getOrNull(NetonHttpClient::class)
            ?: throw AiException(AiError.InvalidRequest(
                "HTTP client must be installed before neton-ai. " +
                "Add `httpClient { ... }` before `ai { ... }` in your Neton.run { ... } block."
            ))
        // Wire httpClient from context (Mode 2 caller doesn't set it manually in DSL)
        config.httpClient = httpClient

        // Merge file config (config/ai.conf) into DSL config
        val fileMap = ConfigLoader.loadModuleConfig(
            moduleName = "ai",
            environment = ConfigLoader.resolveEnvironment(ctx.args),
            args = ctx.args,
        )
        if (fileMap != null) config.applyFileMap(fileMap)

        // Wire logSink from LoggerFactory if caller didn't provide one (Mode 2)
        if (config.logSink == null) {
            val log = ctx.getOrNull(LoggerFactory::class)?.get("neton.ai")
            if (log != null) {
                config.logSink = { line -> log.info(line, emptyMap()) }
            }
        }

        // Build via single source of truth (same path standalone uses)
        val client = AiClientFactory.createFromConfig(config)
        ctx.bind(AiClient::class, client)

        if (config.debug) {
            val log = ctx.getOrNull(LoggerFactory::class)?.get("neton.ai")
            log?.info("AI initialized via Neton component", mapOf(
                "providers" to config.providers.keys.toList(),
                "defaultModel" to config.routing.defaultModel?.toString(),
                "policies" to config.routing.policies.keys.toList(),
            ))
        }
    }

    override suspend fun stop(ctx: NetonContext) {
        ctx.getOrNull(AiClient::class)?.close()
    }
}

/** DSL entry: `ai { providers { ... }; routing { ... } }` */
fun Neton.LaunchBuilder.ai(block: AiConfig.() -> Unit) {
    install(AiComponent, block)
}
