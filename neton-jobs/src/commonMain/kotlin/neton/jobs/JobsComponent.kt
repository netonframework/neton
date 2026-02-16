package neton.jobs

import neton.core.Neton
import neton.core.component.NetonComponent
import neton.core.component.NetonContext
import neton.core.config.ConfigLoader
import neton.jobs.generated.GeneratedJobRegistry
import neton.jobs.internal.CoroutineJobScheduler
import neton.logging.LoggerFactory
import neton.redis.lock.LockManager

object JobsComponent : NetonComponent<JobsConfig> {

    override fun defaultConfig(): JobsConfig = JobsConfig()

    override suspend fun init(ctx: NetonContext, config: JobsConfig) {
        val logger = ctx.getOrNull(LoggerFactory::class)?.get("neton.jobs")

        // 1. 加载 registry（优先 DSL 注入，fallback 到 KSP 生成的 stub）
        val registry = config.registry ?: GeneratedJobRegistry

        // 2. 加载 jobs.conf
        val env = ConfigLoader.resolveEnvironment(ctx.args)
        val jobsConf = ConfigLoader.loadModuleConfig("jobs", configPath = "config", environment = env, args = ctx.args)

        // 3. 全局开关
        val globalEnabled = getGlobalEnabled(jobsConf) ?: config.enabled

        // 4. 解析 [[jobs.items]]，按 id 合并覆盖
        val items = getItems(jobsConf)
        val definitions = registry.jobs.map { def ->
            mergeConfig(def, findOverride(items, def.id))
        }

        // 5. fail-fast：有 SINGLE_NODE 任务但 LockManager 未绑定
        val hasSingleNode = definitions.any { it.enabled && it.mode == ExecutionMode.SINGLE_NODE }
        if (hasSingleNode && ctx.getOrNull(LockManager::class) == null) {
            error(
                "neton-jobs: Found SINGLE_NODE jobs but LockManager is not bound. " +
                        "Install neton-redis (redis { }) or set mode = ExecutionMode.ALL_NODES for all jobs."
            )
        }

        // 6. 绑定 listener（如果有）
        config.listener?.let { ctx.bind(JobExecutionListener::class, it) }

        // 7. 创建调度器并绑定
        val scheduler = CoroutineJobScheduler(
            definitions = definitions,
            globalEnabled = globalEnabled,
            shutdownTimeout = config.shutdownTimeout,
            ctx = ctx
        )
        ctx.bind(JobScheduler::class, scheduler)

        logger?.info(
            "job.init", mapOf(
                "total" to definitions.size,
                "enabled" to definitions.count { it.enabled },
                "singleNode" to definitions.count { it.mode == ExecutionMode.SINGLE_NODE },
                "allNodes" to definitions.count { it.mode == ExecutionMode.ALL_NODES }
            ))
    }

    override suspend fun start(ctx: NetonContext) {
        ctx.get(JobScheduler::class).start()
    }

    override suspend fun stop(ctx: NetonContext) {
        ctx.get(JobScheduler::class).shutdown()
    }

    // --- 配置合并 ---

    @Suppress("UNCHECKED_CAST")
    private fun getGlobalEnabled(conf: Map<String, Any?>?): Boolean? {
        if (conf == null) return null
        val jobs = conf["jobs"] as? Map<String, Any?> ?: return conf["enabled"] as? Boolean
        return jobs["enabled"] as? Boolean
    }

    @Suppress("UNCHECKED_CAST")
    private fun getItems(conf: Map<String, Any?>?): List<Map<String, Any?>> {
        if (conf == null) return emptyList()
        val jobs = conf["jobs"] as? Map<String, Any?> ?: return emptyList()
        return jobs["items"] as? List<Map<String, Any?>> ?: emptyList()
    }

    private fun findOverride(items: List<Map<String, Any?>>, jobId: String): Map<String, Any?>? {
        return items.find { it["id"] == jobId }
    }

    private fun mergeConfig(definition: JobDefinition, override: Map<String, Any?>?): JobDefinition {
        if (override == null) return definition
        return definition.copy(
            schedule = resolveSchedule(definition.schedule, override),
            mode = (override["mode"] as? String)?.let { ExecutionMode.valueOf(it) } ?: definition.mode,
            lockTtlMs = (override["lockTtlMs"] as? Number)?.toLong() ?: definition.lockTtlMs,
            enabled = (override["enabled"] as? Boolean) ?: definition.enabled
        )
    }

    private fun resolveSchedule(current: JobSchedule, override: Map<String, Any?>): JobSchedule {
        val cronOverride = override["cron"] as? String
        val fixedRateOverride = (override["fixedRate"] as? Number)?.toLong()
        val initialDelayOverride = (override["initialDelay"] as? Number)?.toLong()

        return when {
            cronOverride != null -> JobSchedule.Cron(cronOverride)
            fixedRateOverride != null -> {
                val delay = initialDelayOverride
                    ?: (current as? JobSchedule.FixedRate)?.initialDelayMs
                    ?: 0
                JobSchedule.FixedRate(fixedRateOverride, delay)
            }

            initialDelayOverride != null && current is JobSchedule.FixedRate -> {
                current.copy(initialDelayMs = initialDelayOverride)
            }

            else -> current
        }
    }
}

/** DSL 语法糖：jobs { enabled = true; shutdownTimeout = 60.seconds } */
fun Neton.LaunchBuilder.jobs(block: JobsConfig.() -> Unit = {}) {
    install(JobsComponent, block)
}
