package neton.redis.lock

import kotlinx.coroutines.delay
import neton.core.http.LockNotAcquiredException
import neton.redis.RedisClient
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** 规范：≥16 字节随机，32 位 hex（KMP 无 String.format，手写 hex） */
private fun newToken(): String {
    val hex = "0123456789abcdef"
    val bytes = ByteArray(16) { Random.nextBytes(1)[0] }
    return bytes.joinToString("") { b ->
        val i = b.toInt() and 0xff
        "${hex[i shr 4]}${hex[i and 0x0f]}"
    }
}

private const val LOCK_RELEASE_SCRIPT = """
if redis.call("get", KEYS[1]) == ARGV[1] then
    return redis.call("del", KEYS[1])
else
    return 0
end
"""

/**
 * 单 Redis 实例锁实现。满足规范：SET NX PX、Lua token 校验释放、wait 时 delay 轮询、EVALSHA 缓存。
 *
 * 前缀：本层只传 "lock:" + 业务 key 给 RedisClient；keyPrefix（如 "neton"）由 neton-redis 层在 RedisClient 内统一加，
 * 与 cache L2 一致，最终 Redis key = keyPrefix + ":" + "lock:" + key，例如 neton:lock:order:1。
 */
class RedisLockManager(
    private val redis: RedisClient,
    private val lockPrefix: String = "lock"
) : LockManager {

    /** 只拼 lock: + key，不碰 keyPrefix；keyPrefix 由 RedisClient（DefaultRedisClient.fullKey）统一加 */
    private fun redisKey(key: String): String = "$lockPrefix:$key"

    private var releaseScriptSha: String? = null

    private suspend fun loadReleaseSha(): String {
        releaseScriptSha?.let { return it }
        val sha = redis.scriptLoad(LOCK_RELEASE_SCRIPT)
        releaseScriptSha = sha
        return sha
    }

    private suspend fun releaseByLua(redisKey: String, token: String): Boolean {
        val sha = releaseScriptSha ?: loadReleaseSha()
        val result = redis.evalshaToLong(sha, listOf(redisKey), listOf(token))
        if (result != null) return result == 1L
        releaseScriptSha = null
        val fallback = redis.evalToLong(LOCK_RELEASE_SCRIPT, listOf(redisKey), listOf(token))
        return fallback == 1L
    }

    override suspend fun tryLock(
        key: String,
        ttl: Duration,
        wait: Duration,
        retryInterval: Duration
    ): DistributedLock? {
        val rk = redisKey(key)
        val token = newToken()
        val pxMillis = ttl.inWholeMilliseconds.coerceAtLeast(1)

        suspend fun attempt(): Boolean = redis.setIfAbsent(rk, token, pxMillis)

        if (wait <= Duration.ZERO) {
            return if (attempt()) RedisDistributedLock(key, token, rk, this) else null
        }
        val deadline = kotlin.time.TimeSource.Monotonic.markNow() + wait
        while (true) {
            if (attempt()) return RedisDistributedLock(key, token, rk, this)
            if (deadline.hasPassedNow()) return null
            delay(retryInterval)
        }
    }

    override suspend fun <T> withLock(
        key: String,
        ttl: Duration,
        wait: Duration,
        retryInterval: Duration,
        block: suspend () -> T
    ): T {
        val lock = tryLock(key, ttl, wait, retryInterval)
            ?: throw LockNotAcquiredException("Lock not acquired for key: $key", key)
        try {
            return block()
        } finally {
            lock.release()
        }
    }

    internal suspend fun release(redisKey: String, token: String): Boolean =
        releaseByLua(redisKey, token)
}

internal class RedisDistributedLock(
    override val key: String,
    override val token: String,
    private val redisKey: String,
    private val manager: RedisLockManager
) : DistributedLock {
    override suspend fun release(): Boolean = manager.release(redisKey, token)
}
