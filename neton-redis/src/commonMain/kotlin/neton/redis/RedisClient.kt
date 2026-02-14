package neton.redis

import kotlin.time.Duration

/**
 * 全框架唯一 Redis 入口（Laravel/Ktor 风格，非 Spring Template）
 *
 * - redis.get<User>("user:1") / redis.set("user:1", user)
 * - redis.remember("user:$id", 5.minutes) { UserTable.get(id) }
 * - redis.pipeline { set("a", 1); set("b", 2); incr("counter") }
 *
 * 不暴露底层驱动（Lettuce/re.this），可随时替换。
 */
interface RedisClient {

    // ---------- Key/Value ----------
    /** 原始取值（String）；redis.get<User>(key) 由扩展提供类型反序列化） */
    suspend fun getValue(key: String): String?
    suspend fun set(key: String, value: Any, ttl: Duration? = null)
    /** 二进制 value，供 neton-cache；实现须直接存二进制（禁止 Base64），如 Latin1 或驱动原生 bytes */
    suspend fun getBytes(key: String): ByteArray?
    /** 二进制 value，ttl 可选 */
    suspend fun setBytes(key: String, value: ByteArray, ttl: Duration? = null)
    suspend fun delete(key: String)
    suspend fun exists(key: String): Boolean
    suspend fun expire(key: String, ttl: Duration)

    suspend fun incr(key: String): Long
    suspend fun decr(key: String): Long

    // ---------- Hash ----------
    suspend fun hset(key: String, field: String, value: Any)
    suspend fun hget(key: String, field: String): String?
    suspend fun hgetAll(key: String): Map<String, String>

    // ---------- List ----------
    suspend fun lpush(key: String, value: Any)
    suspend fun rpush(key: String, value: Any)
    suspend fun lpop(key: String): String?
    suspend fun lrange(key: String, start: Int, end: Int): List<String>

    // ---------- Set ----------
    suspend fun sadd(key: String, value: Any)
    suspend fun smembers(key: String): Set<String>

    // ---------- Cache 风格（Laravel remember 由扩展提供：redis.remember<User>(key, ttl) { ... }）----------

    // ---------- Pipeline（减少 RTT）----------
    suspend fun pipeline(block: RedisPipeline.() -> Unit)

    /**
     * 按 pattern 收集 key（用于 cache clear）。v1 冻结：优先 SCAN；仅当 allowKeysFallback=true 时允许降级 KEYS（危险，生产禁用）。
     * pattern 如 "cache:user:*"（redis 侧会加 keyPrefix）。
     */
    suspend fun collectScanKeys(matchPattern: String, allowKeysFallback: Boolean = false, block: suspend (String) -> Unit)

    // ---------- 分布式锁（neton-redis lock；SET NX PX + Lua 释放）----------
    /** SET key value NX PX pxMillis；返回 true 表示设置成功（拿到锁） */
    suspend fun setIfAbsent(key: String, value: String, pxMillis: Long): Boolean
    /** SCRIPT LOAD script，返回 SHA1 */
    suspend fun scriptLoad(script: String): String
    /** EVAL script numKeys keys args；返回 Lua 整数结果（如 0/1），非整数或错误时 null */
    suspend fun evalToLong(script: String, keys: List<String>, args: List<String>): Long?
    /** EVALSHA sha numKeys keys args；返回 Lua 整数结果；NOSCRIPT 时返回 null（调用方回退 EVAL） */
    suspend fun evalshaToLong(sha: String, keys: List<String>, args: List<String>): Long?
}

/**
 * Pipeline DSL：在 block 内排队命令，块结束时一次性发送。
 */
interface RedisPipeline {
    fun set(key: String, value: Any, ttl: Duration? = null)
    fun delete(key: String)
    fun incr(key: String)
    fun decr(key: String)
    fun hset(key: String, field: String, value: Any)
    fun lpush(key: String, value: Any)
    fun rpush(key: String, value: Any)
    fun sadd(key: String, value: Any)
}

/** Redis 异常（配置/连接/序列化等） */
class RedisException(message: String, cause: Throwable? = null) : Exception(message, cause)
