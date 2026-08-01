package neton.redis

/**
 * Redis 安装层配置（Layer 1 DSL）
 *
 * ```
 * Neton.run {
 *     redis {
 *         host = "127.0.0.1"
 *         port = 6379
 *     }
 * }
 * ```
 *
 * 每个字段都是可空的，`null` 表示「没设置」。这一点是刻意的：如果用默认值当哨兵，
 * 就无法区分「用户没写 port」和「用户显式写了 port = 6379」——后者会被 redis.conf
 * 里的值悄悄覆盖，而 DSL 本该优先。同样的理由，[fromMap] 读文件时也只填出现过的键。
 *
 * 优先级：DSL > 配置文件 > 内置默认值，由 [resolve] 落定为 [RedisConfig]。
 */
class RedisSettings {
    var host: String? = null
    var port: Int? = null
    var poolSize: Int? = null
    var database: Int? = null
    var timeoutMs: Long? = null
    var debug: Boolean? = null

    /**
     * 密码。这里 null 有歧义——既可能是「没设置」，也可能是「显式清空」，
     * 而 password 的类型本身就可空，没法再用 null 当哨兵。所以额外记一个赋值标记，
     * 让 `redis { password = null }` 能真正覆盖掉 redis.conf 里的密码。
     */
    var password: String? = null
        set(value) {
            field = value
            passwordAssigned = true
        }

    /** password 是否被显式赋过值（包括赋成 null） */
    internal var passwordAssigned: Boolean = false
        private set

    /** 全局 key 前缀，如 "neton"；最终 key = keyPrefix + ":" + key。neton-cache 默认用 "neton:cache:*"。 */
    var keyPrefix: String? = null

    companion object {
        /**
         * 从 `redis.conf` 读取。只填实际出现的键，缺失的保持 null 交给下一层。
         *
         * 类型不对时直接报错，不回退默认值——静默丢配置正是这个模块此前的老毛病。
         */
        fun fromMap(m: Map<String, Any>): RedisSettings = RedisSettings().apply {
            m["host"]?.let { host = it.toString() }
            m["port"]?.let { port = it.intOrFail("port") }
            // maxConnections 是 poolSize 的历史别名
            (m["poolSize"] ?: m["maxConnections"])?.let { poolSize = it.intOrFail("poolSize") }
            m["database"]?.let { database = it.intOrFail("database") }
            m["password"]?.let { password = it.toString() }
            m["timeout"]?.let { timeoutMs = it.longOrFail("timeout") }
            m["debug"]?.let {
                debug = it as? Boolean
                    ?: throw RedisException("redis.conf: 'debug' must be a boolean, got '$it'")
            }
            m["keyPrefix"]?.let { keyPrefix = it.toString() }
        }

        private fun Any.intOrFail(key: String): Int = (this as? Number)?.toInt()
            ?: throw RedisException("redis.conf: '$key' must be a number, got '$this'")

        private fun Any.longOrFail(key: String): Long = (this as? Number)?.toLong()
            ?: throw RedisException("redis.conf: '$key' must be a number, got '$this'")
    }
}

/**
 * 三层落定：DSL 优先，其次配置文件，最后内置默认值。
 *
 * 因为两层都用 null 表示「没设置」，`redis { debug = false }` 能真正关掉
 * `redis.conf` 里的 `debug = true`，而不是被 or 掉。
 */
internal fun resolveRedisConfig(dsl: RedisSettings, fromFile: RedisSettings): RedisConfig {
    val defaults = RedisConfig()
    return RedisConfig(
        host = dsl.host ?: fromFile.host ?: defaults.host,
        port = dsl.port ?: fromFile.port ?: defaults.port,
        poolSize = dsl.poolSize ?: fromFile.poolSize ?: defaults.poolSize,
        database = dsl.database ?: fromFile.database ?: defaults.database,
        // 只有「显式赋过值」才算 DSL 设置过，这样 password = null 是清空而不是沉默
        password = when {
            dsl.passwordAssigned -> dsl.password
            fromFile.passwordAssigned -> fromFile.password
            else -> defaults.password
        },
        timeoutMs = dsl.timeoutMs ?: fromFile.timeoutMs ?: defaults.timeoutMs,
        debug = dsl.debug ?: fromFile.debug ?: defaults.debug,
        keyPrefix = dsl.keyPrefix ?: fromFile.keyPrefix ?: defaults.keyPrefix,
    )
}
