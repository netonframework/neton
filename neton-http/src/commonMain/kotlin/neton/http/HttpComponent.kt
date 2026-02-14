package neton.http

import neton.core.component.HttpConfig
import neton.core.component.NetonComponent
import neton.core.component.NetonContext
import neton.core.http.adapter.HttpAdapter
import neton.core.config.ConfigLoader
import neton.logging.LoggerFactory
import neton.logging.emptyFields

/**
 * HTTP 组件 - 无内部状态，port/config 在 Component，Adapter 内部持有
 */
object HttpComponent : NetonComponent<HttpConfig> {

    override fun defaultConfig(): HttpConfig = HttpConfig()

    override suspend fun init(ctx: NetonContext, config: HttpConfig) {
        val log = ctx.getOrNull(LoggerFactory::class)?.get("neton.http")
        val registry = ctx.getOrNull(neton.core.http.ParamConverterRegistry::class)
            ?: config.converterRegistry
            ?: neton.core.http.DefaultParamConverterRegistry()
        ctx.bindIfAbsent(neton.core.http.ParamConverterRegistry::class, registry)
        // v1.1：HTTP 配置仅来自 application.conf（[server] + [http]），不再读 http.conf；优先级 CLI/ENV > application.conf > DSL
        val appConfig = ConfigLoader.loadApplicationConfig(configPath = "config", environment = ConfigLoader.resolveEnvironment(ctx.args), args = ctx.args)
        val port = resolveInt(appConfig, "server.port") ?: config.port
        val timeout = resolveLong(appConfig, "http.timeout") ?: 30000L
        val maxConnections = resolveInt(appConfig, "http.maxConnections") ?: 1000
        val enableCompression = resolveBoolean(appConfig, "http.enableCompression") ?: true
        val serverConfig = HttpServerConfig(
            port = port,
            timeout = timeout,
            maxConnections = maxConnections,
            enableCompression = enableCompression
        )
        ctx.bind(serverConfig)
        ctx.bind(HttpAdapter::class, KtorHttpAdapter(serverConfig, registry))
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
            is String -> when (raw.lowercase()) { "true", "1", "yes" -> true; "false", "0", "no" -> false; else -> null }
            else -> null
        }
    }
}

data class HttpServerConfig(
    val port: Int,
    val timeout: Long = 30000L,
    val maxConnections: Int = 1000,
    val enableCompression: Boolean = true
) {
    fun printSummary() {
        // 端口等信息由 access log / 启动统计统一输出，此处不再 println
    }
}

/** 语法糖：http { port = 8080 } */
fun neton.core.Neton.LaunchBuilder.http(block: HttpConfig.() -> Unit) {
    install(HttpComponent, block)
}
