package neton.cache

import kotlin.time.Duration

/**
 * 统一缓存接口（v1 冻结）。
 * 用户不关心 L1/L2，只关心 get/put/delete/clear/getOrPut。
 */
interface Cache<K, V> {
    suspend fun get(key: K): V?
    suspend fun put(key: K, value: V, ttl: Duration? = null)
    suspend fun delete(key: K)
    suspend fun clear()
    /** Cache-aside：无则加载并回填；loader 抛异常不缓存，singleflight 共享异常 */
    suspend fun getOrPut(key: K, ttl: Duration? = null, loader: suspend () -> V?): V?
}
