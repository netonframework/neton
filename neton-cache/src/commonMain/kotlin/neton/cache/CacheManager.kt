package neton.cache

import kotlinx.serialization.KSerializer

/**
 * 按 name 获取 Cache，每个 name 对应一套 L1+L2 配置（来自 CacheConfig）。
 * v1：key 类型为 String；getCache 需传入 serializer（或使用扩展 getCache<User>(name)）。
 */
interface CacheManager {
    suspend fun <V : Any> getCache(name: String, serializer: KSerializer<V>): Cache<String, V>
    fun getCacheNames(): Set<String>
}
