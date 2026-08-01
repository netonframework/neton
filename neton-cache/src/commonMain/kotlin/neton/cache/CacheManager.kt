package neton.cache

import kotlinx.serialization.KSerializer

/**
 * 按 name 获取 Cache，每个 name 对应一套 L1+L2 配置（来自 CacheConfig）。
 * v1：key 类型为 String；getCache 需传入 serializer（或使用扩展 getCache<User>(name)）。
 */
interface CacheManager {
    suspend fun <V : Any> getCache(name: String, serializer: KSerializer<V>): Cache<String, V>
    fun getCacheNames(): Set<String>

    /**
     * 按 key 失效，**与值类型无关**。
     *
     * 同一个 name 下每种值类型有各自的 L1 分片（`Cache` 是按 name+serializer 拿的），
     * 而 L2 只按 name 分区。失效必须同时清掉 L2 的那个 key 和**所有**类型分片的 L1，
     * 否则会出现「L2 清了、L1 还留着旧值」的陈旧读。
     *
     * `@CacheEvict` 织入的就是这个方法——它标注的方法常返回 Unit，拿不到值类型。
     */
    suspend fun evict(name: String, key: String)

    /** 清空整个命名缓存（L2 + 所有类型分片的 L1）。对应 `@CacheEvict(allEntries = true)`。 */
    suspend fun evictAll(name: String)
}
