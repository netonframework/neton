package neton.jobs

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

data class JobsConfig(
    /** 全局开关，false = 所有任务不调度。可被 jobs.conf 覆盖。 */
    var enabled: Boolean = true,
    /** Graceful shutdown 超时时间 */
    var shutdownTimeout: Duration = 30.seconds,
    /** 外部注入 registry（测试用）；null 时使用 KSP 生成的 defaultJobRegistry() */
    var registry: JobRegistry? = null,
    /** 外部注入 listener（可选） */
    var listener: JobExecutionListener? = null
)
