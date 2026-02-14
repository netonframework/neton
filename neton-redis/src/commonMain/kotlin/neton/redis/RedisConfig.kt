package neton.redis

/**
 * Redis 安装层配置（Layer 1 DSL）
 *
 * Neton.run {
 *     redis {
 *         host = "127.0.0.1"
 *         port = 6379
 *         poolSize = 16
 *         database = 0
 *     }
 * }
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

    companion object {
        fun fromMap(m: Map<String, Any>): RedisConfig = RedisConfig(
            host = m["host"]?.toString() ?: "127.0.0.1",
            port = (m["port"] as? Number)?.toInt() ?: 6379,
            poolSize = (m["poolSize"] as? Number)?.toInt() ?: (m["maxConnections"] as? Number)?.toInt() ?: 16,
            database = (m["database"] as? Number)?.toInt() ?: 0,
            password = m["password"]?.toString(),
            timeoutMs = (m["timeout"] as? Number)?.toLong() ?: 5000,
            debug = m["debug"] as? Boolean ?: false,
            keyPrefix = m["keyPrefix"]?.toString() ?: "neton",
        )
    }
}
