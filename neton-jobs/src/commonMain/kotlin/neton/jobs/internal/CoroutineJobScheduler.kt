package neton.jobs.internal

import kotlinx.coroutines.*
import neton.core.component.NetonContext
import neton.jobs.ExecutionMode
import neton.jobs.JobContext
import neton.jobs.JobDefinition
import neton.jobs.JobExecutionListener
import neton.jobs.JobResult
import neton.jobs.JobSchedule
import neton.jobs.JobScheduler
import neton.jobs.JobStatus
import neton.logging.Logger
import neton.logging.LoggerFactory
import neton.redis.lock.LockManager
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private fun currentTimeMillis(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()

internal class CoroutineJobScheduler(
    private val definitions: List<JobDefinition>,
    private val globalEnabled: Boolean,
    private val shutdownTimeout: Duration,
    private val ctx: NetonContext
) : JobScheduler {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lockManager: LockManager? = ctx.getOrNull(LockManager::class)
    private val listener: JobExecutionListener? = ctx.getOrNull(JobExecutionListener::class)
    private val loggerFactory: LoggerFactory = ctx.get(LoggerFactory::class)
    private val logger: Logger = loggerFactory.get("neton.jobs")

    private val statuses = mutableMapOf<String, MutableJobStatus>()

    private class MutableJobStatus(
        val definition: JobDefinition,
        var lastFireTime: Long? = null,
        var lastDuration: Long? = null,
        var lastResult: JobResult? = null,
        var nextFireTime: Long? = null,
        var runCount: Long = 0,
        var failCount: Long = 0,
        var running: Boolean = false
    )

    init {
        definitions.forEach { def ->
            statuses[def.id] = MutableJobStatus(definition = def)
        }
    }

    override suspend fun start() {
        val enabledJobs = definitions.filter { it.enabled }
        logger.info(
            "job.scheduler.start", mapOf(
                "total" to definitions.size,
                "enabled" to enabledJobs.size
            )
        )

        for (def in enabledJobs) {
            val jobLogger = loggerFactory.get("neton.jobs.${def.id}")
            scope.launch {
                runScheduleLoop(def, jobLogger)
            }
        }
    }

    override suspend fun shutdown(timeout: Duration) {
        val effectiveTimeout = if (timeout == 30.seconds) shutdownTimeout else timeout
        scope.coroutineContext[Job]?.cancel()
        withTimeoutOrNull(effectiveTimeout) {
            scope.coroutineContext[Job]?.join()
        }
        scope.cancel()
        logger.info("job.scheduler.shutdown", emptyMap())
    }

    override suspend fun trigger(jobId: String) {
        val def = definitions.find { it.id == jobId }
            ?: throw IllegalArgumentException("Job not found: $jobId")
        val jobLogger = loggerFactory.get("neton.jobs.${def.id}")
        scope.launch {
            executeJob(def, jobLogger, skipEnabledCheck = true)
        }
    }

    override fun snapshot(): List<JobStatus> {
        return statuses.values.map { s ->
            JobStatus(
                id = s.definition.id,
                enabled = s.definition.enabled,
                schedule = s.definition.schedule,
                mode = s.definition.mode,
                lastFireTime = s.lastFireTime,
                lastDuration = s.lastDuration,
                lastResult = s.lastResult,
                nextFireTime = s.nextFireTime,
                runCount = s.runCount,
                failCount = s.failCount
            )
        }
    }

    // --- 内部调度 ---

    private suspend fun runScheduleLoop(def: JobDefinition, jobLogger: Logger) {
        when (val schedule = def.schedule) {
            is JobSchedule.FixedRate -> {
                if (schedule.initialDelayMs > 0) {
                    delay(schedule.initialDelayMs)
                }
                while (currentCoroutineContext().isActive) {
                    updateNextFireTime(def, schedule)
                    executeJob(def, jobLogger, skipEnabledCheck = false)
                    delay(schedule.intervalMs)
                }
            }

            is JobSchedule.Cron -> {
                while (currentCoroutineContext().isActive) {
                    val now = currentTimeMillis()
                    val next = CronParser.nextFireTime(schedule.expression, now)
                    if (next < 0) {
                        jobLogger.warn("job.cron.no-next", mapOf("jobId" to def.id, "cron" to schedule.expression))
                        break
                    }
                    val status = statuses[def.id]
                    status?.nextFireTime = next

                    val delayMs = next - currentTimeMillis()
                    if (delayMs > 0) {
                        delay(delayMs)
                    }
                    executeJob(def, jobLogger, skipEnabledCheck = false)
                }
            }
        }
    }

    private fun updateNextFireTime(def: JobDefinition, schedule: JobSchedule.FixedRate) {
        val status = statuses[def.id] ?: return
        status.nextFireTime = currentTimeMillis() + schedule.intervalMs
    }

    private suspend fun executeJob(def: JobDefinition, jobLogger: Logger, skipEnabledCheck: Boolean) {
        // 1. 检查全局开关
        if (!skipEnabledCheck && !globalEnabled) return

        // 2. 检查任务 enabled
        if (!skipEnabledCheck && !def.enabled) return

        // 3. 检查串行（如果已在运行，跳过）
        val status = statuses[def.id] ?: return
        if (status.running) return
        status.running = true

        val fireTime = currentTimeMillis()

        try {
            when (def.mode) {
                ExecutionMode.SINGLE_NODE -> {
                    val lm = lockManager
                        ?: error("LockManager not bound but job '${def.id}' requires SINGLE_NODE. Install neton-redis or set mode=ALL_NODES.")
                    val lock = lm.tryLock("job:${def.id}", def.lockTtlMs.milliseconds)
                    if (lock == null) {
                        // SKIPPED
                        status.lastFireTime = fireTime
                        status.lastResult = JobResult.SKIPPED
                        jobLogger.info("job.skipped", mapOf("jobId" to def.id, "fireTime" to fireTime))
                        listener?.onSkipped(def.id, fireTime)
                        return
                    }
                    try {
                        doRun(def, jobLogger, fireTime, status)
                    } finally {
                        lock.release()
                    }
                }

                ExecutionMode.ALL_NODES -> {
                    doRun(def, jobLogger, fireTime, status)
                }
            }
        } finally {
            status.running = false
        }
    }

    private suspend fun doRun(def: JobDefinition, jobLogger: Logger, fireTime: Long, status: MutableJobStatus) {
        val jobContext = JobContext(
            jobId = def.id,
            ctx = ctx,
            fireTime = fireTime,
            logger = jobLogger
        )

        jobLogger.info("job.started", mapOf("jobId" to def.id, "fireTime" to fireTime))
        listener?.onStart(def.id, fireTime)

        val startTime = currentTimeMillis()
        try {
            val executor = def.factory(ctx)
            executor.run(jobContext)
            val duration = currentTimeMillis() - startTime

            status.lastFireTime = fireTime
            status.lastDuration = duration
            status.lastResult = JobResult.SUCCESS
            status.runCount++

            jobLogger.info("job.done", mapOf("jobId" to def.id, "fireTime" to fireTime, "duration" to duration))
            listener?.onSuccess(def.id, fireTime, duration)
        } catch (e: CancellationException) {
            throw e // 不捕获取消异常
        } catch (e: Throwable) {
            val duration = currentTimeMillis() - startTime

            status.lastFireTime = fireTime
            status.lastDuration = duration
            status.lastResult = JobResult.FAILED
            status.runCount++
            status.failCount++

            jobLogger.error(
                "job.failed",
                mapOf(
                    "jobId" to def.id,
                    "fireTime" to fireTime,
                    "duration" to duration,
                    "error" to (e.message ?: e.toString())
                )
            )
            listener?.onFailure(def.id, fireTime, duration, e)
        }
    }
}
