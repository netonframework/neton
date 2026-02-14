package neton.cache.internal

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import neton.cache.Cache
import neton.cache.CacheConfig
import kotlin.time.Duration

/**
 * L1 + L2 两级缓存；getOrPut 进程内 per-key singleflight；loader 异常不缓存，等待方共享异常。
 */
internal class TwoLevelCache<V : Any>(
    private val config: CacheConfig,
    private val l1: L1Cache<String, V>,
    private val l2: RedisCacheBacking,
    private val serializer: KSerializer<V>,
) : Cache<String, V> {

    private val singleflight = mutableMapOf<String, kotlinx.coroutines.Deferred<V?>>()
    private val singleflightMutex = Mutex()

    override suspend fun get(key: String): V? {
        val fullKey = key
        if (config.enableL1) {
            l1.get(fullKey)?.let { return it }
        }
        val raw = l2.get(fullKey) ?: return null
        val value = when {
            CacheValueHeader.isNull(raw) -> null
            else -> CacheValueHeader.unwrap(raw)?.let { (payload, kind) -> CacheCodec.decode(serializer, payload, kind) }
        } ?: return null
        if (config.enableL1) {
            val ttl = config.nullTtl ?: config.ttl
            if (value != null) l1.put(fullKey, value, ttl)
        }
        return value
    }

    override suspend fun put(key: String, value: V, ttl: Duration?) {
        val effectiveTtl = ttl ?: config.ttl
        val payload = CacheCodec.encode(serializer, value, config.codec)
        val wrapped = CacheValueHeader.wrapValue(payload, config.codec)
        l2.set(key, wrapped, effectiveTtl)
        if (config.enableL1) l1.put(key, value, effectiveTtl)
    }

    override suspend fun delete(key: String) {
        l2.delete(key)
        if (config.enableL1) l1.delete(key)
    }

    override suspend fun clear() {
        l2.clear()
        if (config.enableL1) l1.clear()
    }

    override suspend fun getOrPut(key: String, ttl: Duration?, loader: suspend () -> V?): V? = coroutineScope {
        get(key)?.let { return@coroutineScope it }
        val effectiveTtl = ttl ?: config.ttl
        val deferred = singleflightMutex.withLock {
            singleflight.getOrPut(key) {
                async {
                    try {
                        val loaded = loader()
                        if (loaded != null) {
                            val payload = CacheCodec.encode(serializer, loaded, config.codec)
                            l2.set(key, CacheValueHeader.wrapValue(payload, config.codec), effectiveTtl)
                            if (config.enableL1) l1.put(key, loaded, effectiveTtl)
                        } else if (config.nullTtl != null) {
                            l2.set(key, CacheValueHeader.wrapNull(), config.nullTtl)
                        }
                        loaded
                    } catch (e: Throwable) {
                        throw e
                    } finally {
                        singleflightMutex.withLock { singleflight.remove(key) }
                    }
                }
            }
        }
        deferred.await()
    }
}
