package neton.jobs

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

interface JobScheduler {
    /** 启动所有已启用任务的调度 coroutine */
    suspend fun start()

    /** 优雅停机：停止调度新任务，等待执行中的任务完成（超时后取消） */
    suspend fun shutdown(timeout: Duration = 30.seconds)

    /** 立即触发一次指定任务（忽略 enabled 开关，但仍走 SINGLE_NODE 锁） */
    suspend fun trigger(jobId: String)

    /** 获取所有任务的运行状态快照 */
    fun snapshot(): List<JobStatus>
}
