package neton.cache.internal

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import neton.cache.Cache
import neton.cache.CacheConfig
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

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

    private data class Lookup<T>(val value: T?)

    override suspend fun get(key: String): V? = lookup(key)?.value

    private suspend fun lookup(key: String): Lookup<V>? {
        val fullKey = key
        if (config.enableL1) {
            l1.get(fullKey)?.let { return Lookup(it) }
        }
        val raw = l2.get(fullKey) ?: return null
        if (CacheValueHeader.isNull(raw)) return Lookup(null)
        val value = CacheValueHeader.unwrap(raw)?.let { (payload, kind) ->
            CacheCodec.decode(serializer, payload, kind)
        } ?: return null
        // Redis GET does not expose remaining TTL. Refilling with the configured
        // TTL would extend the lifetime of an almost-expired value.
        return Lookup(value)
    }

    override suspend fun put(key: String, value: V, ttl: Duration?) {
        val effectiveTtl = ttl ?: config.ttl
        val payload = CacheCodec.encode(serializer, value, config.codec)
        val wrapped = CacheValueHeader.wrapValue(payload, config.codec)
        val started = kotlin.time.TimeSource.Monotonic.markNow()
        l2.set(key, wrapped, effectiveTtl)
        if (config.enableL1) l1.put(key, value, effectiveTtl.inWholeMilliseconds.milliseconds - started.elapsedNow())
    }

    override suspend fun delete(key: String) {
        l2.delete(key)
        if (config.enableL1) l1.delete(key)
    }

    override suspend fun clear() {
        l2.clear()
        if (config.enableL1) l1.clear()
    }

    /**
     * 只清进程内 L1，不碰 L2。
     *
     * 供 [neton.cache.CacheManager.evict] 使用：L2 按 name 分区、只需删一次，
     * 而每种值类型有各自的 L1 分片，必须逐个清。
     */
    internal suspend fun deleteL1(key: String) {
        if (config.enableL1) l1.delete(key)
    }

    /** 只清进程内 L1，不碰 L2。供 [neton.cache.CacheManager.evictAll] 使用。 */
    internal suspend fun clearL1() {
        if (config.enableL1) l1.clear()
    }

    override suspend fun getOrPut(key: String, ttl: Duration?, loader: suspend () -> V?): V? {
        lookup(key)?.let { return it.value }
        val candidate = CompletableDeferred<V?>()
        val deferred = singleflightMutex.withLock {
            singleflight.getOrPut(key) { candidate }
        }
        if (deferred !== candidate) return deferred.await()
        try {
            // Another flight may have filled the cache between lookup and registration.
            val cached = lookup(key)
            val loaded = if (cached != null) cached.value else loader().also { value ->
                if (value != null) put(key, value, ttl)
                else config.nullTtl?.let { l2.set(key, CacheValueHeader.wrapNull(), it) }
            }
            candidate.complete(loaded)
            return loaded
        } catch (error: Throwable) {
            candidate.completeExceptionally(error)
            throw error
        } finally {
            withContext(NonCancellable) {
                singleflightMutex.withLock { singleflight.remove(key) }
            }
        }
    }
}
