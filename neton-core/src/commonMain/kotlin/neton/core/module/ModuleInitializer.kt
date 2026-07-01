package neton.core.module

import neton.core.component.NetonContext

/**
 * 模块初始化器接口
 *
 * 每个业务模块（如 member、payment、platform）通过 KSP 自动生成一个实现类，
 * 在 initialize(ctx) 中完成该模块的所有注册工作（路由、仓库、校验器、定时任务、配置器）。
 *
 * 主应用通过 modules() DSL 显式声明加载哪些模块：
 * ```kotlin
 * Neton.run(args) {
 *     modules(MemberModuleInitializer, PaymentModuleInitializer)
 *     http { port = 8080 }
 *     routing { }
 * }
 * ```
 */
interface ModuleInitializer {

    /** 模块唯一标识，对应 KSP 参数 neton.moduleId */
    val moduleId: String

    /**
     * 模块注册统计信息，KSP 编译期生成。
     * key 为领域名（routes, validators, repositories, jobs, configs），value 为数量。
     */
    val stats: Map<String, Int> get() = emptyMap()

    /**
     * 声明本模块依赖的其他模块 ID 列表。
     * 框架在启动时按拓扑序执行 initialize，确保依赖模块先于当前模块初始化。
     * 缺失依赖或循环依赖会 fail-fast 抛出异常。
     *
     * 通过 KSP 选项 `neton.moduleDependsOn` 配置（逗号分隔）：
     * ```
     * ksp { arg("neton.moduleDependsOn", "common,auth") }
     * ```
     */
    val dependsOn: List<String> get() = emptyList()

    /**
     * 初始化模块：注册路由、仓库、校验器、定时任务、配置器到 ctx。
     * 框架在组件 init/configure 之后、prepare/start 与 Context freeze 之前调用。
     */
    fun initialize(ctx: NetonContext)

    /**
     * 模块的 migration sources。默认空,无 schema 的模块不需要 override。
     *
     * 一个模块可以声明多份(三方言并存),`application.kexe migrate` 会按 application 当前
     * 链接的 sqlx4k driver 过滤匹配的 [MigrationDialect]。
     *
     * SPEC §0.3 / §五 / §六。框架不解释 [MigrationSource.moduleId] 的业务语义,只把它当 opaque
     * namespace 写入 history 表 `(module_id, version)` 复合键。
     *
     * 执行时机: **只在** `./application.kexe migrate up` 子命令下,**正常启动绝不**触发(SPEC §0.6
     * 红线;[initialize] 内禁止调用 migration engine)。
     */
    fun migrations(): List<MigrationSource> = emptyList()
}
