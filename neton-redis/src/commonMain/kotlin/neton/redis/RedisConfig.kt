package neton.redis

/**
 * 落定后的 Redis 运行时配置，字段全部非空。
 *
 * 面向用户的入口是 [RedisSettings]（`redis { }` 的 receiver，字段可空）；
 * 三层优先级由 [resolveRedisConfig] 落定成这个类型。
 */
data class RedisConfig(
    var host: String = "127.0.0.1",
    var port: Int = 6379,
    var poolSize: Int = 16,
    var database: Int = 0,
    var password: String? = null,
    var timeoutMs: Long = 5000,
    var debug: Boolean = false,
    /** 全局 key 前缀，如 "neton"；最终 key = keyPrefix + ":" + key。neton-cache 默认用 "neton:cache:*"。 */
    var keyPrefix: String = "neton",
) {
    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        if (host.isBlank()) errors.add("Redis host cannot be blank")
        if (port !in 1..65535) errors.add("Redis port must be 1..65535")
        if (poolSize <= 0) errors.add("Redis poolSize must be positive")
        if (database < 0) errors.add("Redis database must be non-negative")
        return errors
    }

}
