package neton.cache.internal

import kotlin.time.Duration
import neton.redis.RedisClient

/**
 * L2 强绑定 neton-redis；key 命名空间为 cache:name。v1 冻结：clear 优先 SCAN，仅当 allowKeysClear=true 时允许 KEYS 降级。
 */
internal class RedisCacheBacking(
    private val redis: RedisClient,
    /** 逻辑前缀，如 "cache:user"，不含 redis 全局 keyPrefix */
    private val keyPrefix: String,
    /** 为 true 时允许 clear 降级使用 KEYS（危险，生产禁用） */
    private val allowKeysClear: Boolean,
) {
    private fun fullKey(key: String): String = "$keyPrefix:$key"

    suspend fun get(key: String): ByteArray? = redis.getBytes(fullKey(key))

    suspend fun set(key: String, value: ByteArray, ttl: Duration?) {
        redis.setBytes(fullKey(key), value, ttl)
    }

    suspend fun delete(key: String) {
        redis.delete(fullKey(key))
    }

    /** 优先 SCAN；仅当 allowKeysClear=true 时允许 KEYS 降级（WARN 由 redis 侧打印） */
    suspend fun clear() {
        val keys = mutableListOf<String>()
        val prefix = "$keyPrefix:"
        val pattern = prefix.flatMap { if (it in "\\*?[]") listOf('\\', it) else listOf(it) }.joinToString("") + "*"
        suspend fun flush() {
            if (keys.isEmpty()) return
            redis.pipeline { keys.forEach { delete(it) } }
            keys.clear()
        }
        redis.collectScanKeys(pattern, allowKeysFallback = allowKeysClear) { key ->
            if (key.startsWith(prefix)) keys.add(key)
            if (keys.size >= 256) flush()
        }
        flush()
    }
}
