package neton.core.annotations

/**
 * Logic 组件编译期装配注解。
 *
 * **语义: 标记该类为模块所有的、由 KSP 在模块初始化阶段编译期装配进 NetonContext 的对象**
 * （compile-time assembled into NetonContext）。不是 runtime DI / 不是 ServiceLoader /
 * 不是反射 — K/N 唯一可行路径是编译期生成对象图。
 *
 * 取代 ModuleInitializer 里手写的 `val x = X(...); ctx.bind(X::class, x)` 机械样板。
 *
 * 架构定位（database spec §2.4 分层: Controller → Logic → Table → Model）：
 * - Logic 是唯一业务聚合层，本注解即该层的装配标记。
 * - 兼容期: 现存 Repository / Service 命名的类同样可标注（装配语义与类名无关），
 *   命名收敛是独立重构。
 *
 * 装配契约（P0.1 冻结，全部编译期）：
 * 1. 只支持 primary constructor 注入（secondary constructor 不参与装配）。
 * 2. 参数按类型解析:
 *    - `neton.logging.Logger` → `loggerFactory.get(logger名)`。**logger 名必须通过本注解
 *      [logger] 参数显式声明** — 缺省 / 空串是编译错误。Logger 语义名是 prod 日志 grep
 *      契约，不允许被静默改名。
 *    - 其它 class 类型 → `ctx.get(类型::class)`（含其它 @Logic 类、框架组件如
 *      LoggerFactory / DbContext / RedisClient）。
 *    - 非 class 类型（泛型参数 / 函数类型）→ 编译错误。
 * 3. @Logic 之间按构造依赖做拓扑排序；循环依赖编译错误（错误信息含完整依赖路径）。
 * 4. **生成的 bind 永远 non-overriding**（absent 才 bind）——手写 bootstrap 先 bind 的
 *    对象永远优先，escape hatch 不需要任何新机制。此语义是冻结契约。
 * 5. 任何解析失败都是 KSP 编译错误 — 绝不 fallback 到 runtime 再炸。
 *
 * 自动化判据是**构造可机械化**，不是代码薄: 600 行 raw SQL 的类只要构造函数无副作用
 * 就可以标注。以下情况不要标注 — 保留手写 bootstrap：
 * - 构造或装配有副作用（launch() / setEngine() / 启动 recovery）
 * - 构造期读 env / config 做分支（如 Redis fallback）
 * - 需要被手写 bootstrap 强制覆盖的对象
 *
 * 生成物: `neton.module.{moduleId}.generated.{ModuleId}LogicInitializer`，
 * 由手写 ModuleInitializer 显式调用，或经 ModuleFragmentSink 聚合进生成的模块初始化器。
 * 需要 KSP 选项 `neton.moduleId`。
 *
 * @param logger 构造函数有 `Logger` 参数时**必须**声明的 logger 语义名
 *   （如 "game.kind_repo"）。构造函数没有 Logger 参数时可省略。
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class Logic(val logger: String = "")
