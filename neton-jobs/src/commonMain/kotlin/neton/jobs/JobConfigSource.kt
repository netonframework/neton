package neton.jobs

/**
 * 运行期任务配置覆盖源（框架钩子）。
 *
 * 默认 [JobsComponent] 只从代码 `@Job`（`GeneratedJobRegistry`）+ `jobs.conf` 文件读取任务定义与覆盖。
 * 绑定一个 [JobConfigSource] 到 `NetonContext` 后，`JobsComponent.prepare` 会**额外**向它拉取每个任务的
 * 运行期覆盖（enabled / cron / fixedRate / mode / lockTtlMs），并以 **DB 源优先于 jobs.conf** 合并。
 *
 * 用途：让「后台定时任务管理」（如 module-infra 的 `infra_jobs` 表 + admin UI）成为运行期调度真源——
 * 代码 `@Job` 定义 handler 能力，DB 决定启用/调度/触发。不绑定时行为与现状完全一致（向后兼容）。
 *
 * override 的 Map 形态与 `jobs.conf` 的 `jobs.items` 一致：
 * `{"id": "<@Job.id>", "enabled": true, "cron": "...", "fixedRate": 5000, "initialDelay": 0, "mode": "...", "lockTtlMs": 30000}`
 * —— 只需带要覆盖的键，未带的键沿用代码默认。
 */
interface JobConfigSource {
    /**
     * 返回按任务 id 的运行期覆盖列表。`prepare` 阶段调用一次（suspend，可访问 DB）。
     * 实现方可在此顺带把代码里已注册但 DB 缺失的任务 upsert 进管理表（便于后台可见）。
     */
    suspend fun overrides(): List<Map<String, Any?>>
}
