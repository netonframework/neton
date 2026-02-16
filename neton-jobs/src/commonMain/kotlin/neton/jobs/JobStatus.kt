package neton.jobs

data class JobStatus(
    val id: String,
    val enabled: Boolean,
    val schedule: JobSchedule,
    val mode: ExecutionMode,
    val lastFireTime: Long?,
    val lastDuration: Long?,
    val lastResult: JobResult?,
    /** 下次预计触发时间（epoch millis）。精度：fixedRate=毫秒，cron=分钟（秒数固定为 0） */
    val nextFireTime: Long?,
    val runCount: Long,
    val failCount: Long
)

enum class JobResult {
    SUCCESS, FAILED, SKIPPED
}
