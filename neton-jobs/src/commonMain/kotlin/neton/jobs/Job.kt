package neton.jobs

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class Job(
    /** 任务唯一标识，全局不可重复。建议 kebab-case，如 "clean-expired-tokens" */
    val id: String,
    /** cron 表达式（5 段：分 时 日 月 周）。与 fixedRate 二选一，不可同时设置。 */
    val cron: String = "",
    /** 固定间隔（毫秒）。与 cron 二选一，不可同时设置。 */
    val fixedRate: Long = 0,
    /** 首次执行延迟（毫秒）。默认 0 表示立即执行。仅对 fixedRate 有效，cron 忽略此值。 */
    val initialDelay: Long = 0,
    /** 执行模式。默认 SINGLE_NODE，集群只允许一个节点执行。 */
    val mode: ExecutionMode = ExecutionMode.SINGLE_NODE,
    /** 分布式锁 TTL（毫秒）。仅 SINGLE_NODE 模式有效。应大于任务最大执行时间。 */
    val lockTtlMs: Long = 30_000,
    /** 是否启用。可被 jobs.conf 覆盖。 */
    val enabled: Boolean = true
)
