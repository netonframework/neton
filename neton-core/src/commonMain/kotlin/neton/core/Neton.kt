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
import neton.core.module.ModuleInitializer
import neton.logging.Logger
import neton.logging.LoggerFactory
import kotlin.reflect.KClass

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
        private val moduleInitializers = mutableListOf<ModuleInitializer>()
        private val deferredBindings = mutableListOf<(NetonContext) -> Unit>()

        @Suppress("UNCHECKED_CAST")
        private val installs = mutableListOf<Pair<NetonComponent<*>, (Any).() -> Unit>>()

        internal fun getApp(): Neton = app

        /**
         * 预绑定实例到 NetonContext（DSL 阶段收集，启动时执行）。
         * 用于在模块初始化之前绑定共享的构建器或服务。
         */
        fun <T : Any> bind(type: KClass<T>, impl: T) {
            deferredBindings.add { ctx -> ctx.bind(type, impl) }
        }

        /**
         * 传入 KSP 生成的配置注册表，供 @NetonConfig 自动应用
         * 不调用则使用空注册表（不应用任何业务配置器）
         */
        fun configRegistry(registry: NetonConfigRegistry) {
            configRegistry = registry
        }

        /**
         * 注册模块初始化器
         *
         * 每个模块由 KSP 生成一个 ModuleInitializer 实现，在此显式声明加载哪些模块。
         * 框架按声明顺序依次调用 initialize(ctx)，完成路由、仓库、校验器等注册。
         *
         * 执行时机（已冻结）：
         * 1. 所有 component init/start 完成后
         * 2. 用户 configure 块执行前
         * 3. httpAdapter.start 之前
         *
         * modules() 的调用位置不影响执行时机，DSL 仅收集，不立即执行。
         *
         * ```kotlin
         * Neton.run(args) {
         *     modules(SystemModuleInitializer, MemberModuleInitializer)
         *     http { port = 8080 }
         *     routing { }
         * }
         * ```
         */
        fun modules(vararg initializers: ModuleInitializer) {
            moduleInitializers.addAll(initializers)
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
            ctx.bind(NetonContext::class, ctx)
            ctx.bind(NetonConfigRegistry::class, configRegistry ?: EmptyNetonConfigRegistry)
            // 执行 DSL 阶段收集的延迟绑定
            for (binding in deferredBindings) {
                binding(ctx)
            }
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
            if (moduleInitializers.isNotEmpty()) {
                // 依赖验证 + 拓扑排序
                val sorted = topologicalSort(moduleInitializers, log)

                // 输出模块加载概览
                val moduleIds = sorted.map { it.moduleId }
                log?.info(
                    "modules.loaded", mapOf(
                        "version" to VERSION,
                        "count" to sorted.size,
                        "modules" to moduleIds.joinToString(", ")
                    )
                )

                // 逐模块初始化（按拓扑序）
                for (initializer in sorted) {
                    try {
                        initializer.initialize(ctx)
                        val statsMap = mutableMapOf<String, Any>("moduleId" to initializer.moduleId)
                        initializer.stats.forEach { (k, v) -> statsMap[k] = v }
                        log?.info("module.initialized", statsMap)
                    } catch (e: Exception) {
                        log?.error(
                            "module.init.failed",
                            mapOf("moduleId" to initializer.moduleId, "error" to (e.message ?: "")),
                            cause = e
                        )
                        throw e
                    }
                }
                // 汇总所有模块统计
                val totalStats = mutableMapOf<String, Int>()
                for (initializer in sorted) {
                    initializer.stats.forEach { (k, v) -> totalStats[k] = (totalStats[k] ?: 0) + v }
                }
                val summary = mutableMapOf<String, Any>(
                    "version" to VERSION,
                    "totalModules" to sorted.size,
                    "moduleIds" to moduleIds
                )
                totalStats.forEach { (k, v) -> summary["total" + k.replaceFirstChar { c -> c.uppercaseChar() }] = v }
                log?.info("modules.summary", summary)
            } else {
                // 兼容模式：使用全局 GeneratedInitializer（单模块项目或未迁移项目）
                try {
                    neton.core.generated.GeneratedInitializer.initialize(ctx)
                } catch (_: Exception) {
                }
            }
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

        private fun configureRequestEngineFromCtx(
            ctx: NetonContext,
            securityConfig: SecurityConfiguration
        ): RequestEngine {
            val requestEngine =
                ctx.getOrNull(RequestEngine::class) ?: error("Routing component not installed - add routing { }")
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
            httpAdapter.start(ctx) { coldStartMs ->
                printStartupBanner(
                    httpAdapter,
                    coldStartMs,
                    ConfigLoader.resolveEnvironment(ctx.args)
                )
            }
        }

        /**
         * 拓扑排序模块初始化器，确保依赖模块先于当前模块初始化。
         * fail-fast：缺失依赖或循环依赖直接抛异常。
         * 无依赖关系的模块保持用户声明顺序。
         */
        private fun topologicalSort(initializers: List<ModuleInitializer>, log: Logger?): List<ModuleInitializer> {
            // 无依赖声明，直接按原序返回
            if (initializers.all { it.dependsOn.isEmpty() }) return initializers

            val byId = mutableMapOf<String, ModuleInitializer>()
            for (init in initializers) {
                if (byId.containsKey(init.moduleId)) {
                    error("Duplicate moduleId '${init.moduleId}' in modules()")
                }
                byId[init.moduleId] = init
            }

            // 校验：所有 dependsOn 引用的模块必须存在
            for (init in initializers) {
                for (dep in init.dependsOn) {
                    if (dep !in byId) {
                        error("Module '${init.moduleId}' depends on '$dep', but '$dep' is not registered in modules()")
                    }
                }
            }

            // Kahn's algorithm（稳定拓扑排序）
            val inDegree = mutableMapOf<String, Int>()
            for (init in initializers) inDegree[init.moduleId] = 0
            for (init in initializers) {
                for (dep in init.dependsOn) {
                    inDegree[init.moduleId] = (inDegree[init.moduleId] ?: 0) + 1
                }
            }

            // 用原始顺序的队列保证稳定性
            val queue = ArrayDeque<String>()
            for (init in initializers) {
                if (inDegree[init.moduleId] == 0) queue.addLast(init.moduleId)
            }

            val result = mutableListOf<ModuleInitializer>()
            while (queue.isNotEmpty()) {
                val id = queue.removeFirst()
                result.add(byId[id]!!)
                // 找所有依赖 id 的模块，减少入度
                for (init in initializers) {
                    if (id in init.dependsOn) {
                        val newDegree = (inDegree[init.moduleId] ?: 1) - 1
                        inDegree[init.moduleId] = newDegree
                        if (newDegree == 0) queue.addLast(init.moduleId)
                    }
                }
            }

            if (result.size != initializers.size) {
                val remaining = initializers.filter { it.moduleId !in result.map { r -> r.moduleId }.toSet() }
                    .map { it.moduleId }
                error("Circular module dependency detected among: ${remaining.joinToString(", ")}")
            }

            val before = initializers.map { it.moduleId }
            val after = result.map { it.moduleId }
            if (before != after) {
                log?.info("modules.order", mapOf("before" to before, "after" to after))
            }
            return result
        }

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

    fun <T : Any> get(type: kotlin.reflect.KClass<T>): T = (ctx ?: error("NetonContext is not initialized. Ensure the application is fully started before accessing components.")).get(type)

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
        CoreLog.logOrBootstrap().info(
            "neton.security.status",
            mapOf("enabled" to isEnabled, "authenticators" to authenticatorCount, "guards" to guardCount)
        )
    }
}

/** 框架层启动 banner，仅在服务器成功启动后打印 */
private fun printStartupBanner(adapter: HttpAdapter, coldStartMs: Long, environment: String) {
    val pid = neton.core.config.getProcessId()
    kotlin.io.println(
        """

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

    """.trimMargin()
    )
}
