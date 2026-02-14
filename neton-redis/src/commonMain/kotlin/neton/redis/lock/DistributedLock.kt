package neton.redis.lock

/**
 * 分布式锁句柄。释放必须通过 token 校验（Lua GET==token 再 DEL），避免误删其他客户端锁。
 */
interface DistributedLock {
    /** 业务 key（未加 lock 前缀） */
    val key: String
    /** 唯一 token，释放时校验 */
    val token: String
    /** 释放锁；仅当 Redis 中该 key 的 value 等于本 token 时才 DEL，返回是否成功释放 */
    suspend fun release(): Boolean
}
