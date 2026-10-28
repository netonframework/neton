package neton.jobs.internal

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val executionJob = SupervisorJob()
    private val executionScope = CoroutineScope(executionJob + Dispatchers.Default)
    private val lifecycleMutex = Mutex()
    private var stopping = false
    private val lockManager: LockManager? = ctx.getOrNull(LockManager::class)
    private val listener: JobExecutionListener? = ctx.getOrNull(JobExecutionListener::class)
    private val loggerFactory: LoggerFactory = ctx.get(LoggerFactory::class)
    private val logger: Logger = loggerFactory.get("neton.jobs")
    private val started = MutableStateFlow(false)

    private val statuses = mutableMapOf<String, MutableJobStatus>()

    private class MutableJobStatus(
        val definition: JobDefinition,
    ) {
        val execution = Mutex()
        val snapshot = MutableStateFlow(JobStatus(
            definition.id, definition.enabled, definition.schedule, definition.mode,
            null, null, null, null, 0, 0,
        ))
    }

    init {
        definitions.forEach { def ->
            statuses[def.id] = MutableJobStatus(definition = def)
        }
    }

    override suspend fun start() = lifecycleMutex.withLock {
        check(!stopping) { "Job scheduler has been shut down" }
        if (!started.compareAndSet(false, true)) return@withLock
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
        require(effectiveTimeout > Duration.ZERO && effectiveTimeout.isFinite()) { "Shutdown timeout must be positive and finite" }
        lifecycleMutex.withLock {
            stopping = true
            // Stop scheduling without cancelling accepted executions.
            scope.cancel()
            executionJob.complete()
        }
        try {
            withTimeoutOrNull(effectiveTimeout) { executionJob.join() }
        } finally {
            // Cooperative cancellation after the drain deadline (or caller cancellation).
            executionJob.cancel()
        }
        logger.info("job.scheduler.shutdown", emptyMap())
    }

    override suspend fun trigger(jobId: String) {
        val def = definitions.find { it.id == jobId }
            ?: throw IllegalArgumentException("Job not found: $jobId")
        val jobLogger = loggerFactory.get("neton.jobs.${def.id}")
        lifecycleMutex.withLock {
            check(!stopping) { "Job scheduler has been shut down" }
            executionScope.launch { executeJob(def, jobLogger, skipEnabledCheck = true) }
        }
    }

    private suspend fun executeScheduled(def: JobDefinition, logger: Logger) {
        val execution = lifecycleMutex.withLock {
            if (stopping) null else executionScope.async { executeJob(def, logger, skipEnabledCheck = false) }
        }
        // Cancelling the scheduling waiter does not cancel this separately owned execution.
        execution?.await()
    }

    override fun snapshot(): List<JobStatus> {
        return statuses.values.map { it.snapshot.value }
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
                    executeScheduled(def, jobLogger)
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
                    status?.snapshot?.update { it.copy(nextFireTime = next) }

                    val delayMs = next - currentTimeMillis()
                    if (delayMs > 0) {
                        delay(delayMs)
                    }
                    executeScheduled(def, jobLogger)
                }
            }
        }
    }

    private fun updateNextFireTime(def: JobDefinition, schedule: JobSchedule.FixedRate) {
        val status = statuses[def.id] ?: return
        status.snapshot.update { it.copy(nextFireTime = currentTimeMillis() + schedule.intervalMs) }
    }

    private suspend fun executeJob(def: JobDefinition, jobLogger: Logger, skipEnabledCheck: Boolean) {
        // 1. 检查全局开关
        if (!skipEnabledCheck && !globalEnabled) return

        // 2. 检查任务 enabled
        if (!skipEnabledCheck && !def.enabled) return

        // 3. 检查串行（如果已在运行，跳过）
        val status = statuses[def.id] ?: return
        if (!status.execution.tryLock()) return

        val fireTime = currentTimeMillis()

        try {
            when (def.mode) {
                ExecutionMode.SINGLE_NODE -> {
                    val lm = lockManager
                        ?: error("LockManager not bound but job '${def.id}' requires SINGLE_NODE. Install neton-redis or set mode=ALL_NODES.")
                    val lock = lm.tryLock("job:${def.id}", def.lockTtlMs.milliseconds)
                    if (lock == null) {
                        // SKIPPED
                        status.snapshot.update { it.copy(lastFireTime = fireTime, lastResult = JobResult.SKIPPED) }
                        jobLogger.info("job.skipped", mapOf("jobId" to def.id, "fireTime" to fireTime))
                        notifyListener { listener?.onSkipped(def.id, fireTime) }
                        return
                    }
                    try {
                        doRun(def, jobLogger, fireTime, status)
                    } finally {
                        withContext(NonCancellable) {
                            val released = withTimeoutOrNull(5_000) { lock.release(); true } ?: false
                            if (!released) jobLogger.warn("job.lock.release.timeout", mapOf("jobId" to def.id))
                        }
                    }
                }

                ExecutionMode.ALL_NODES -> {
                    doRun(def, jobLogger, fireTime, status)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            jobLogger.error("job.infrastructure.failed", mapOf("jobId" to def.id), error)
        } finally {
            status.execution.unlock()
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
        notifyListener { listener?.onStart(def.id, fireTime) }

        val startTime = kotlin.time.TimeSource.Monotonic.markNow()
        try {
            val executor = def.factory(ctx)
            executor.run(jobContext)
            val duration = startTime.elapsedNow().inWholeMilliseconds

            status.snapshot.update { it.copy(lastFireTime = fireTime, lastDuration = duration,
                lastResult = JobResult.SUCCESS, runCount = it.runCount + 1) }

            jobLogger.info("job.done", mapOf("jobId" to def.id, "fireTime" to fireTime, "duration" to duration))
            notifyListener { listener?.onSuccess(def.id, fireTime, duration) }
        } catch (e: CancellationException) {
            throw e // 不捕获取消异常
        } catch (e: Throwable) {
            val duration = startTime.elapsedNow().inWholeMilliseconds

            status.snapshot.update { it.copy(lastFireTime = fireTime, lastDuration = duration,
                lastResult = JobResult.FAILED, runCount = it.runCount + 1, failCount = it.failCount + 1) }

            jobLogger.error(
                "job.failed",
                mapOf(
                    "jobId" to def.id,
                    "fireTime" to fireTime,
                    "duration" to duration,
                    "error" to (e.message ?: e.toString())
                )
            )
            notifyListener { listener?.onFailure(def.id, fireTime, duration, e) }
        }
    }

    private suspend fun notifyListener(block: suspend () -> Unit) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            logger.error("job.listener.failed", cause = error)
        }
    }
}
