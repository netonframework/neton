package neton.jobs

import neton.core.component.NetonContext

data class JobDefinition(
    val id: String,
    val schedule: JobSchedule,
    val mode: ExecutionMode,
    val lockTtlMs: Long,
    val enabled: Boolean,
    /** 工厂函数：从 NetonContext 创建 JobExecutor 实例（KSP 生成，自动解析构造函数依赖） */
    val factory: (NetonContext) -> JobExecutor
)
