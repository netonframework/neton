package neton.jobs

import neton.core.component.NetonContext
import neton.logging.Logger

class JobContext(
    /** 当前任务 ID（来自 @Job.id） */
    val jobId: String,
    /** 应用上下文，可通过 ctx.get<T>() 访问所有已注册服务 */
    val ctx: NetonContext,
    /** 本次触发时间（UTC epoch millis） */
    val fireTime: Long,
    /** 预绑定的 Logger（tag = "neton.jobs.{jobId}"） */
    val logger: Logger
)
