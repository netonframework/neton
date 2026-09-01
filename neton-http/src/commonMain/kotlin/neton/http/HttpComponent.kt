package neton.http

import neton.core.component.CorsConfig
import neton.core.component.HttpConfig
import neton.core.component.NetonComponent
import neton.core.component.NetonContext
import neton.core.http.ParamConverterRegistry
import neton.core.http.adapter.HttpAdapter
import neton.core.http.adapter.HttpAdapterFactory
import neton.core.http.adapter.HttpServerConfig
import neton.core.config.ConfigLoader

/**
 * HTTP 组件 - 无内部状态，port/config 在 Component，Adapter 内部持有
 */
class HttpComponent(
    private val adapterFactory: HttpAdapterFactory,
) : NetonComponent<HttpConfig> {

    override fun defaultConfig(): HttpConfig = HttpConfig()

    override suspend fun init(ctx: NetonContext, config: HttpConfig) {
        val registry = ctx.getOrNull(neton.core.http.ParamConverterRegistry::class)
            ?: config.converterRegistry
            ?: neton.core.http.DefaultParamConverterRegistry()
        ctx.bindIfAbsent(neton.core.http.ParamConverterRegistry::class, registry)
        // v1.1：HTTP 配置仅来自 application.conf（[server] + [http]），不再读 http.conf；优先级 CLI/ENV > application.conf > DSL
        val appConfig = ConfigLoader.loadApplicationConfig(
            configPath = "config",
            environment = ConfigLoader.resolveEnvironment(ctx.args),
            args = ctx.args
        )
        val port = resolveInt(appConfig, "server.port") ?: config.port
        val timeout = resolveLong(appConfig, "http.timeout") ?: 30000L
        val maxConnections = resolveInt(appConfig, "http.maxConnections") ?: 1000
        val enableCompression = resolveBoolean(appConfig, "http.enableCompression") ?: true
        // CORS: application.conf [cors] 优先，fallback 到 DSL
        val corsConfig = resolveCorsConfig(appConfig) ?: config.corsConfig
        val serverConfig = HttpServerConfig(
            port = port,
            timeout = timeout,
            maxConnections = maxConnections,
            enableCompression = enableCompression,
            corsConfig = corsConfig
        )
        ctx.bind(serverConfig)
        ctx.bind(HttpAdapter::class, adapterFactory(serverConfig, registry))
    }

    private fun resolveInt(config: Map<String, Any?>?, path: String): Int? {
        val raw = ConfigLoader.getConfigValue(config, path) ?: return null
        return when (raw) {
            is Number -> raw.toInt()
            is String -> raw.toIntOrNull()
            else -> null
        }
    }

    private fun resolveLong(config: Map<String, Any?>?, path: String): Long? {
        val raw = ConfigLoader.getConfigValue(config, path) ?: return null
        return when (raw) {
            is Number -> raw.toLong()
            is String -> raw.toLongOrNull()
            else -> null
        }
    }

    private fun resolveBoolean(config: Map<String, Any?>?, path: String): Boolean? {
        val raw = ConfigLoader.getConfigValue(config, path) ?: return null
        return when (raw) {
            is Boolean -> raw
            is String -> when (raw.lowercase()) {
                "true", "1", "yes" -> true; "false", "0", "no" -> false; else -> null
            }

            else -> null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun resolveCorsConfig(appConfig: Map<String, Any?>?): CorsConfig? {
        val corsSection = appConfig?.let { ConfigLoader.getConfigValue(it, "cors") as? Map<String, Any?> }
            ?: return null
        return CorsConfig().apply {
            (corsSection["allowedOrigins"] as? List<*>)?.filterIsInstance<String>()?.let { allowedOrigins = it }
            (corsSection["allowedMethods"] as? List<*>)?.filterIsInstance<String>()?.let { allowedMethods = it }
            (corsSection["allowedHeaders"] as? List<*>)?.filterIsInstance<String>()?.let { allowedHeaders = it }
            (corsSection["allowCredentials"] as? Boolean)?.let { allowCredentials = it }
            (corsSection["maxAgeSeconds"] as? Number)?.toLong()?.let { maxAgeSeconds = it }
        }
    }
}


/** Installs an application-selected server adapter using a Native-safe constructor reference. */
fun neton.core.Neton.LaunchBuilder.http(
    adapterFactory: HttpAdapterFactory,
    block: HttpConfig.() -> Unit = {},
) {
    install(HttpComponent(adapterFactory), block)
}
