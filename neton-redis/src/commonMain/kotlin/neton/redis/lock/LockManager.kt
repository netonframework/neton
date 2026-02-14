package neton.redis.lock

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * 分布式锁管理。v1：单 Redis 实例 SET NX PX + Lua token 校验释放；lock key 前缀由实现层自动加。
 */
interface LockManager {
    /**
     * 尝试获取锁。
     * @param key 业务 key（如 "order:{orderId}"）；实现层自动加 lock 前缀
     * @param ttl 锁过期时间（必填，防死锁）
     * @param wait 等待时间，ZERO 表示不等待、立即返回
     * @param retryInterval 轮询间隔；wait > 0 时必须 delay(retryInterval)，禁止 busy loop
     * @return 获取成功返回 DistributedLock，否则 null
     */
    suspend fun tryLock(
        key: String,
        ttl: Duration,
        wait: Duration = Duration.ZERO,
        retryInterval: Duration = 50L.milliseconds
    ): DistributedLock?

    /**
     * 获取锁后执行 block，结束后在 finally 中释放（token 校验释放）。
     * 若 wait == ZERO 且未拿到锁，抛 [neton.core.http.LockNotAcquiredException]。
     */
    suspend fun <T> withLock(
        key: String,
        ttl: Duration,
        wait: Duration = Duration.ZERO,
        retryInterval: Duration = 50L.milliseconds,
        block: suspend () -> T
    ): T
}
