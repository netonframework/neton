package neton.logging

/**
 * 类级注解：表示该类需要注入一个「属于当前类」的 Logger。
 *
 * 使用方式：在构造中声明 `log: Logger`（或 `logger: Logger`），
 * 由 KSP 在生成 Controller/Service 实例化代码时注入
 * `ctx.get(LoggerFactory::class).get("完全限定类名")`。
 *
 * 业务层禁止直接调用 LoggerFactory.get()；仅通过本注解 + 构造注入获取 Logger。
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class Log
