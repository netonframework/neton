package neton.jobs.generated

import neton.jobs.JobDefinition
import neton.jobs.JobRegistry

/**
 * 默认空 Registry（stub）。
 * 当用户项目中有 @Job 类时，KSP JobProcessor 会生成同名类覆盖此 stub。
 */
object GeneratedJobRegistry : JobRegistry {
    override val jobs: List<JobDefinition> = emptyList()
}
