package neton.jobs

interface JobExecutionListener {
    /** 任务开始执行 */
    suspend fun onStart(jobId: String, fireTime: Long) {}

    /** 任务执行成功 */
    suspend fun onSuccess(jobId: String, fireTime: Long, duration: Long) {}

    /** 任务执行失败 */
    suspend fun onFailure(jobId: String, fireTime: Long, duration: Long, error: Throwable) {}

    /** 任务被跳过（SINGLE_NODE 模式未获取到锁） */
    suspend fun onSkipped(jobId: String, fireTime: Long) {}
}
