package neton.core.annotations

/**
 * 限流作用域 — 决定按谁限流
 */
enum class RateLimitScope {
    /** 按认证用户限流（需 userId） */
    USER,
    /** 按客户端 IP 限流 */
    IP,
    /** 路由全局限流（所有请求共享计数） */
    GLOBAL,
    /** 自定义 key（需配合 key 参数使用 query:/header:/path: 前缀） */
    CUSTOM
}

/**
 * 限流策略 — 决定使用哪种算法
 *
 * v1 仅支持 FIXED_WINDOW，其他策略在编译期报错
 * v2 将补充 SLIDING_WINDOW 和 TOKEN_BUCKET 实现
 */
enum class RateLimitStrategy {
    /** 固定窗口：当前窗口内计数，窗口结束重置 */
    FIXED_WINDOW,
    /** 滑动窗口：按时间滑动计算（v2 实现） */
    SLIDING_WINDOW,
    /** 令牌桶：允许突发，长期平均（v2 实现） */
    TOKEN_BUCKET
}

/**
 * 限流注解 — 声明式限流
 *
 * 由 KSP 编译期解析并生成 RouteDefinition.rateLimit 元数据。
 * 不依赖运行时反射。
 *
 * 示例：
 * ```
 * @Post("/sms/send")
 * @RateLimit(windowSeconds = 60, maxRequests = 5, scope = RateLimitScope.USER)
 * suspend fun sendSms() { ... }
 * ```
 *
 * @param windowSeconds  时间窗口（秒）
 * @param maxRequests    窗口内最大请求数
 * @param scope          限流作用域（USER / IP / GLOBAL / CUSTOM）
 * @param key            自定义 key（仅 scope=CUSTOM 时有效，支持 query:/header:/path: 前缀）
 * @param strategy       限流策略（v1 仅支持 FIXED_WINDOW）
 * @param message        被限流时的返回消息
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
annotation class RateLimit(
    val windowSeconds: Int,
    val maxRequests: Int,
    val scope: RateLimitScope = RateLimitScope.USER,
    val key: String = "",
    val strategy: RateLimitStrategy = RateLimitStrategy.FIXED_WINDOW,
    val message: String = "Too many requests"
)
