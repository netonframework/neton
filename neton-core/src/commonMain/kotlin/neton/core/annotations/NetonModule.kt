package neton.core.annotations

/**
 * 模块声明锚点（MANIFEST-P1）。
 *
 * 标在模块内一个空 object 上，由 KSP `ModuleInitializerProcessor` 生成
 * `neton.module.{id}.generated.{Id}ModuleManifest : ModuleInitializer`，
 * 聚合本模块全部 KSP 产物（@Logic 装配 / 路由 / jobs / validators / configs）、
 * 按约定 FQN 探测到的 migrations 与手写 runtime bootstrap。
 *
 * ```kotlin
 * @NetonModule(id = "game", dependsOn = ["privchat"])
 * object GameModule
 * ```
 *
 * 契约（P1 冻结）：
 * - [id] **必须与 KSP 选项 `neton.moduleId` 一致**，不一致是编译错误。
 *   注解是声明面（代码内可见、可审查），KSP arg 是各 processor 共享的机制载体，双源互证。
 * - [dependsOn] 在注解声明；与 ksp arg `neton.moduleDependsOn` 同时存在时注解优先。
 * - 约定 FQN 探测（编译期 resolver，存在才生成对应调用）：
 *   - `init.generated.{Id}MigrationResources`（gradle task 生成的 SQL 常量）
 *     → manifest override `migrations()`。注解**不带** migration path 参数 —
 *     SQL 必须编译进 binary，K/N 运行期不读 .sql（migration SPEC §0.3 铁律）。
 *   - `init.{Id}RuntimeBootstrap`（手写 object，`fun initialize(ctx: NetonContext)`）
 *     → 在 @Logic 装配之后、路由注册之前调用。engine / scheduler / worker 等
 *     有副作用的复杂装配全部放这里，不注解化。
 * - initialize 顺序冻结: configs → logics(@Logic) → RuntimeBootstrap → routes → jobs → validators。
 *
 * 模块侧最终形态：手写 initializer 退化为薄壳
 * `object GameModuleInitializer : ModuleInitializer by GameModuleManifest`，
 * application 的 `modules(...)` 不变（P2 才做 application 级自动聚合）。
 *
 * @param id 模块唯一标识，必须匹配 `[a-z][a-z0-9-]*` 且与 ksp arg `neton.moduleId` 一致。
 * @param dependsOn 依赖的其他模块 id；框架启动时按拓扑序 initialize。
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class NetonModule(
    val id: String,
    val dependsOn: Array<String> = [],
)
