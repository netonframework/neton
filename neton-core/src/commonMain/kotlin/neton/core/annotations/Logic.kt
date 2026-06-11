package neton.core.annotations

/**
 * Logic 组件自动装配注解（LOGIC-P0）。
 *
 * 标注业务 Logic / 数据访问类，由 KSP `LogicProcessor` 在编译期生成构造 + 注册代码，
 * 取代 ModuleInitializer 里手写的 `val x = X(...); ctx.bind(X::class, x)` 机械样板。
 *
 * 架构定位（database spec §2.4 分层: Controller → Logic → Table → Model）：
 * - Logic 是唯一业务聚合层，本注解即该层的装配标记。
 * - P0 兼容期: 现存 Repository / Service 命名的类同样可标注（装配语义与类名无关），
 *   命名收敛是后续独立重构。
 *
 * 装配规则（P0 冻结，全部编译期，无反射）：
 * 1. 只支持 primary constructor 注入。
 * 2. 参数按类型解析:
 *    - `neton.logging.Logger` → `loggerFactory.get(logger名)`，logger 名取本注解
 *      [logger] 参数；缺省用类全限定名。
 *    - 其它类型 → `ctx.get(类型::class)`（含其它 @Logic 类、框架组件如
 *      LoggerFactory / DbContext / RedisClient）。
 * 3. @Logic 之间按构造依赖做拓扑排序，循环依赖编译期报错。
 * 4. 生成代码一律 "absent 才 bind"（`ctx.getOrNull == null` 守卫）——手写
 *    bootstrap 先 bind 的对象永远优先，escape hatch 不需要任何新机制。
 *
 * 生成物: `neton.module.{moduleId}.generated.{ModuleId}LogicInitializer`，
 * 由手写 ModuleInitializer 显式调用，或经 ModuleFragmentSink 聚合进生成的模块初始化器。
 *
 * 有副作用 / 条件分支 / 复杂构造的 runtime 对象（engine、scheduler、worker.launch()）
 * 不要标注本注解 — 保留在手写 bootstrap。
 *
 * @param logger 当构造函数有 `Logger` 参数时使用的 logger 名（保留既有日志语义名，
 *   prod 日志 grep 依赖）。空串时退化为类全限定名。
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class Logic(val logger: String = "")
