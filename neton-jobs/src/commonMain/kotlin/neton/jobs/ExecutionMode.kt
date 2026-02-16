package neton.jobs

/** 任务执行模式 */
enum class ExecutionMode {
    /** 每个实例都执行（不使用分布式锁） */
    ALL_NODES,

    /** 多实例只允许一个执行（使用 LockManager 协调） */
    SINGLE_NODE
}
