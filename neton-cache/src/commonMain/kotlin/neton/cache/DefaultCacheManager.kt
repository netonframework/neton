package neton.cache

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import neton.cache.internal.L1Cache
import neton.cache.internal.RedisCacheBacking
import neton.cache.internal.TwoLevelCache
import neton.redis.RedisClient

/**
 * v1：L2 强绑定 neton-redis；按 name + serializer 惰性创建 TwoLevelCache。
 */
class DefaultCacheManager(
    private val redis: RedisClient,
    private val configs: Map<String, CacheConfig>,
) : CacheManager {

    private val caches = mutableMapOf<String, TwoLevelCache<*>>()
    private val mutex = Mutex()

    private fun cacheKey(name: String, serializer: KSerializer<*>): String =
        "$name:${serializer.descriptor.serialName}"

    @Suppress("UNCHECKED_CAST")
    override suspend fun <V : Any> getCache(name: String, serializer: KSerializer<V>): Cache<String, V> {
        val config = configs[name] ?: error("Unknown cache name: $name")
        val key = cacheKey(name, serializer)
        return mutex.withLock {
            var cache = caches[key] as? TwoLevelCache<V>
            if (cache == null) {
                val l1 = L1Cache<String, V>(
                    maxSize = config.maxSize ?: 1000,
                    defaultTtl = config.ttl,
                )
                val l2 = RedisCacheBacking(redis, "cache:${config.name}", config.allowKeysClear)
                cache = TwoLevelCache(config, l1, l2, serializer)
                caches[key] = cache
            }
            cache
        }
    }

    override fun getCacheNames(): Set<String> = configs.keys.toSet()
}

/** 扩展：reified 获取 Cache<String, V> */
suspend inline fun <reified V : Any> CacheManager.getCache(name: String): Cache<String, V> =
    getCache(name, kotlinx.serialization.serializer<V>())
