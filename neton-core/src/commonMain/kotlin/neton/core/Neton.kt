package neton.core

import neton.core.http.adapter.HttpAdapter
import neton.core.interfaces.*
import neton.core.config.ConfigLoader
import neton.core.config.EmptyNetonConfigRegistry
import neton.core.config.NetonConfigRegistry
import neton.core.http.*
import neton.core.security.AuthenticationContext
import neton.core.component.NetonComponent
import neton.core.component.NetonContext
import neton.logging.Logger
import neton.logging.LoggerFactory

/**
 * Neton Web 框架核心类
 *
 * 使用 Neton.run(args) { http { }; routing { }; onStart { } } 启动应用
 */
class Neton private constructor() {
    
    companion object {
        /** 框架版本，用于启动 banner */
        const val VERSION = "1.0.0-beta1"
        private const val DEFAULT_PORT = 8080  // 与 application.conf 默认端口保持一致
        
        /**
         * 🚀 主要的 Neton DSL 入口方法 - 现代化体验 (非挂起版本)
         * 
         * 自动处理协程启动，用户无需手动 runBlocking
         * 
         * 灵感来自：
         * - Spring Boot 的 SpringApplication.run()
         * - Ktor 的 embeddedServer {}
         * - Compose 的 compose {}
         * 
         * 使用示例：
         * ```kotlin
         * fun main(args: Array<String>) {
         *     Neton.run(args) {
         *         components(
         *             SecurityComponent(),
         *             RoutingComponent(),
         *             HttpComponent()
         *         )
         *         
         *         configure {
         *             security {
         *                 registerMockAuthenticator("admin", listOf("admin", "user"))
         *             }
         *         }
         *         
         *         onStart {
         *             val log = get(LoggerFactory::class).get("neton.app")
         *             log.info("app.ready", mapOf("url" to "http://localhost:\${getPort()}", "port" to getPort()))
         *         }
         *     }
         * }
         * ```
         */
        fun run(args: Array<String>, block: LaunchBuilder.() -> Unit) {
            try {
                val builder = LaunchBuilder()
                builder.block()
                launchInCoroutineScope(builder, args)
            } catch (e: Exception) {
                throw e
            }
        }

        /**
         * 🚀 推荐的统一启动方法 - 自动处理协程启动
         * 
         * 优势：
         * - 自动处理协程启动，无需手动 runBlocking
         * - 单一入口点，语义清晰
         * - Builder 模式统一管理配置顺序
         * - 避免复杂的链式调用
         */
        fun launch(args: Array<String>, block: LaunchBuilder.() -> Unit) {
            val builder = LaunchBuilder()
            builder.block()
            
            // 在同步环境中启动协程
            launchInCoroutineScope(builder, args)
        }
        
        /**
         * 平台启动：startSync 内完成 init → start → httpAdapter.start（阻塞至服务器停止）
         */
        private fun launchInCoroutineScope(builder: LaunchBuilder, args: Array<String>) {
            try {
                builder.startSync(args)
            } catch (e: Exception) {
                CoreLog.logOrBootstrap().error("neton.launch.failed", mapOf("message" to (e.message ?: "")), cause = e)
            }
        }
        
    }
    
    // 存储配置块，按正确顺序执行
    private var userConfigBlock: (suspend KotlinApplication.() -> Unit)? = null
    private var componentConfigBlock: (ComponentConfigurator.() -> Unit)? = null
    
    /**
     * 组件配置器 - 配置已注册的组件（从 ctx 获取服务）
     */
    class ComponentConfigurator(private val ctx: NetonContext, private val app: Neton) {
        
        /**
         * 确定服务器端口 - 配置优先级处理
         * 
         * 优先级：命令行参数 > application.conf > 默认值
         */
        private fun determineServerPort(applicationConfig: Map<String, Any>?, commandLinePort: Int): Int {
            val log = CoreLog.logOrBootstrap()
            if (commandLinePort != DEFAULT_PORT) {
                log.info("neton.port.source", mapOf("source" to "commandLine", "port" to commandLinePort))
                return commandLinePort
            }
            applicationConfig?.let { config ->
                val port = ConfigLoader.getConfigValue(config, "server.port") as? Number
                if (port != null) {
                    val appPort = port.toInt()
                    log.info("neton.port.source", mapOf("source" to "application.conf", "port" to appPort))
                    return appPort
                }
            }
            log.info("neton.port.source", mapOf("source" to "default", "port" to DEFAULT_PORT))
            return DEFAULT_PORT
        }
        
        /**
         * 配置安全组件
         */
        fun security(configure: SecurityBuilder.() -> Unit) {
            CoreLog.logOrBootstrap().info("neton.config.security")
            ctx.getOrNull(SecurityBuilder::class)?.let { configure(it) }
                ?: error("SecurityBuilder not in ctx. Add: security { }")
            CoreLog.logOrBootstrap().info("neton.config.security.done")
        }

        fun routing(configure: RequestEngine.() -> Unit) {
            CoreLog.logOrBootstrap().info("neton.config.routing")
            ctx.getOrNull(RequestEngine::class)?.let { configure(it) }
                ?: error("RequestEngine not in ctx. Add: routing { }")
            CoreLog.logOrBootstrap().info("neton.config.routing.done")
        }

        fun http(configure: HttpAdapter.() -> Unit) {
            CoreLog.logOrBootstrap().info("neton.config.http")
            ctx.getOrNull(HttpAdapter::class)?.let { configure(it) }
                ?: error("HttpAdapter not in ctx. Add: http { port = 8080 }")
            CoreLog.logOrBootstrap().info("neton.config.http.done")
        }
    }

    /**
     * 🚀 Launch Builder - 统一应用启动构建器
     * 
     * 简化的 API，将复杂的链式调用封装成更清晰的配置块
     */
    class LaunchBuilder {
        private val app = Neton()
        private var userBlock: (suspend KotlinApplication.() -> Unit)? = null
        private var configRegistry: NetonConfigRegistry? = null
        
        @Suppress("UNCHECKED_CAST")
        private val installs = mutableListOf<Pair<NetonComponent<*>, (Any).() -> Unit>>()
        
        internal fun getApp(): Neton = app
        
        /**
         * 传入 KSP 生成的配置注册表，供 @NetonConfig 自动应用
         * 不调用则使用空注册表（不应用任何业务配置器）
         */
        fun configRegistry(registry: NetonConfigRegistry) {
            configRegistry = registry
        }
        
        /**
         * 安装组件：install(HttpComponent) { port = 8080 } 或 http { port = 8080 }
         */
        fun <C : Any> install(component: NetonComponent<C>, block: C.() -> Unit) {
            installs.add(component to (block as (Any).() -> Unit))
        }
        
        /**
         * 应用启动后的回调
         */
        fun onStart(block: suspend KotlinApplication.() -> Unit) {
            userBlock = block
        }
        
        /**
         * 同步启动方法 - 供 launch API 使用
         */
        fun startSync(args: Array<String>) {
            try {
                if (installs.isEmpty()) {
                    error("No components installed. Add at least: http { port = 8080 }")
                }
                kotlinx.coroutines.runBlocking { startSyncWithInstalls(args) }
            } catch (e: Exception) {
                throw e
            }
        }

        /** install 路径：defaultConfig → block → init → [start] */
        private suspend fun startSyncWithInstalls(args: Array<String>) {
            val ctx = NetonContext(args)
            ctx.bind(NetonConfigRegistry::class, configRegistry ?: EmptyNetonConfigRegistry)
            val env = ConfigLoader.resolveEnvironment(args)
            val appConfig = ConfigLoader.loadApplicationConfig("config", env, args)
            @Suppress("UNCHECKED_CAST")
            val loggingSection = appConfig?.let { ConfigLoader.getConfigValue(it, "logging") as? Map<String, Any?> }
            ctx.bindIfAbsent(LoggerFactory::class, neton.logging.defaultLoggerFactory(loggingSection))
            val log = ctx.getOrNull(LoggerFactory::class)?.get("neton.core")
            CoreLog.log = log
            for ((component, block) in installs) {
                val config = component.defaultConfig()
                block(config)
                @Suppress("UNCHECKED_CAST")
                (component as NetonComponent<Any>).init(ctx, config)
            }
            for ((component, _) in installs) {
                @Suppress("UNCHECKED_CAST")
                (component as NetonComponent<Any>).start(ctx)
            }
            NetonContext.setCurrent(ctx)
            try {
                initializeInfrastructure(ctx, log)
                executeComponentConfiguration(ctx, log)
                val securityConfig = buildSecurityConfigurationFromCtx(ctx, log)
                configureRequestEngineFromCtx(ctx, securityConfig)
                executeUserConfiguration(log)
                startHttpServerSync(ctx, args, log)
            } finally {
                NetonContext.setCurrent(null)
                // v1.1: 逆序 stop 所有组件，单组件异常仅打 warn 不中断
                for (i in installs.indices.reversed()) {
                    val (component, _) = installs[i]
                    try {
                        @Suppress("UNCHECKED_CAST")
                        (component as NetonComponent<Any>).stop(ctx)
                    } catch (_: Exception) {
                    }
                }
            }
        }
        
        private fun initializeInfrastructure(ctx: NetonContext, log: Logger?) {
            try {
                neton.core.generated.GeneratedInitializer.initialize(ctx)
            } catch (_: Exception) {}
        }
        
        private fun executeComponentConfiguration(ctx: NetonContext, log: Logger?) {
            app.componentConfigBlock?.let { configBlock ->
                val configurator = ComponentConfigurator(ctx, app)
                configBlock(configurator)
            }
        }
        
        private fun buildSecurityConfigurationFromCtx(ctx: NetonContext, log: Logger?): SecurityConfiguration {
            val securityBuilder = ctx.getOrNull(SecurityBuilder::class)
            return if (securityBuilder != null) {
                securityBuilder.build()
            } else {
                SecurityConfiguration(
                    isEnabled = false,
                    authenticatorCount = 0,
                    guardCount = 0,
                    authenticationContext = neton.core.security.DisabledAuthenticationContext(),
                    defaultAuthenticator = null,
                    defaultGuard = null
                )
            }
        }
        
        private fun configureRequestEngineFromCtx(ctx: NetonContext, securityConfig: SecurityConfiguration): RequestEngine {
            val requestEngine = ctx.getOrNull(RequestEngine::class) ?: error("Routing component not installed - add routing { }")
            requestEngine.setAuthenticationContext(securityConfig.authenticationContext)
            ctx.bind(SecurityConfiguration::class, securityConfig)
            return requestEngine
        }
        
        private fun executeUserConfiguration(log: Logger?) {
            // userBlock 在 startHttpServerSync 中通过 block(KotlinApplication(actualPort, ctx)) 调用
        }
        
        private suspend fun startHttpServerSync(ctx: NetonContext, args: Array<String>, log: Logger?) {
            val httpAdapter = ctx.get<HttpAdapter>()
            val actualPort = httpAdapter.port()
            userBlock?.let { block ->
                block(KotlinApplication(actualPort, ctx))
            }
            httpAdapter.start(ctx) { coldStartMs -> printStartupBanner(httpAdapter, coldStartMs, ConfigLoader.resolveEnvironment(ctx.args)) }
        }
        
        private fun printStartupStatistics(requestEngine: RequestEngine, port: Int, log: Logger?) {}
    }
}

/**
 * Kotlin 应用程序上下文
 */
class KotlinApplication(
    private val port: Int,
    private val ctx: NetonContext? = null
) {
    fun getPort(): Int = port

    fun <T : Any> get(type: kotlin.reflect.KClass<T>): T = ctx!!.get(type)

    /** inline 泛型：get<LoggerFactory>() */
    inline fun <reified T : Any> get(): T = get(T::class)
    
    fun printInfo(message: String) {
        CoreLog.logOrBootstrap().info("neton.info", mapOf("message" to message))
    }
    
    fun getRegisteredRoutes(): List<RouteDefinition> {
        return ctx?.getOrNull(RequestEngine::class)?.getRoutes() ?: emptyList()
    }
    
    fun getSecurityStatus(): SecurityConfiguration {
        return ctx?.getOrNull(SecurityConfiguration::class)
            ?: ctx?.getOrNull(SecurityBuilder::class)?.build()
            ?: SecurityConfiguration(false, 0, 0, neton.core.security.DisabledAuthenticationContext(), null, null)
    }
}

/**
 * 安全状态信息
 */
data class SecurityStatus(
    val isEnabled: Boolean,
    val authenticatorCount: Int,
    val guardCount: Int
) {
    fun printSummary() {
        CoreLog.logOrBootstrap().info("neton.security.status", mapOf("enabled" to isEnabled, "authenticators" to authenticatorCount, "guards" to guardCount))
    }
}

/** 框架层启动 banner，仅在服务器成功启动后打印 */
private fun printStartupBanner(adapter: HttpAdapter, coldStartMs: Long, environment: String) {
    val pid = neton.core.config.getProcessId()
    kotlin.io.println("""

        |░█▀█░█▀▀░▀█▀░█▀█░█▀█
        |░█░█░█▀▀░░█░░█░█░█░█
        |░▀░▀░▀▀▀░░▀░░▀▀▀░▀░▀░
        |
        |Neton ${Neton.VERSION}
        |Kotlin/Native Runtime
        |
        |Adapter     : ${adapter.adapterName()}
        |Environment : $environment
        |Port        : ${adapter.port()}
        |PID         : $pid
        |Cold Start  : $coldStartMs ms
        |
        |Ready → http://localhost:${adapter.port()}

    """.trimMargin())
}
