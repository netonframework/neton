package neton.redis.lock

/**
 * 方法级分布式锁。key 支持模板 "order:{orderId}"，实现层自动加 lock 前缀。
 * v1 不暴露 failCode：LockNotAcquiredException 固定映射 HTTP 409。
 */
@Target(AnnotationTarget.FUNCTION)
annotation class Lock(
    val key: String,
    val ttlMs: Long = 10_000,
    val waitMs: Long = 0,
    val retryMs: Long = 50
)
