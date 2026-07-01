package neton.core.annotations

/**
 * 模块声明锚点（MANIFEST-P1 / P1.1）。
 *
 * 标在模块内一个空 object 上，由 KSP `ModuleInitializerProcessor` 生成
 * `neton.module.{id}.generated.{Id}ModuleManifest : ModuleInitializer`，
 * 聚合本模块全部 KSP 产物（@Logic 装配 / 路由 / jobs / validators / configs）、
 * 按约定 FQN 探测到的 migrations 与手写 runtime bootstrap。
 *
 * ```kotlin
 * @Module(dependsOn = ["privchat"], migrations = true)
 * object GameModule
 * ```
 *
 * Neton 注解体系: `@Module` / `@Logic` / `@Controller` / `@Table` —
 * 模块声明 / 业务对象装配 / 路由 / 数据模型。
 *
 * 契约（P1.1 冻结）：
 * - [id] 可省略。解析顺序：
 *   1. 注解显式 id → 必须与 KSP 选项 `neton.moduleId` 一致（编译错误，双源互证）。
 *   2. 省略 → 取 KSP 选项 `neton.moduleId`（机制载体，各 processor 共享，必配）。
 *      同时尝试从 package 推导（末段；若末段是 init/module/bootstrap 则取倒数第二段），
 *      推导结果与 ksp arg 不一致时输出编译警告（提示配置可能错位），不阻断。
 * - [dependsOn] 是架构语义，不自动推导，显式声明。
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
 * @param id 模块唯一标识；省略时按上述规则解析。显式时必须匹配 `[a-z][a-z0-9-]*`
 *   且与 ksp arg `neton.moduleId` 一致。
 * @param dependsOn 依赖的其他模块 id；框架启动时按拓扑序 initialize。
 * @param migrations 模块是否声明 schema migration；为 true 时生成资源缺失会编译失败。
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class Module(
    val id: String = "",
    val dependsOn: Array<String> = [],
    val migrations: Boolean = false,
)
