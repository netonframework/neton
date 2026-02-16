package neton.jobs

/** 定时任务执行接口。一个实现类 = 一个定时任务。 */
interface JobExecutor {
    /** 任务执行逻辑。框架保证每次调度调用一次，异常由框架捕获并通知 listener。 */
    suspend fun run(ctx: JobContext)
}
