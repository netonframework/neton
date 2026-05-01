package neton.security.jwt

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

/**
 * 设备级 `session_version` 短缓存（spec TOKEN_UNIFICATION_SPEC v1.3 §7.3 / §7.4）。
 *
 * 用法：
 * - 命中且版本一致 → 跳过 introspect，直接放行
 * - 命中但版本 < token claim → fail-closed（旧 token 用了新 cache 的更高版本不可能；
 *   等于"被 bump 了"或恶意篡改），抓权威路径走 introspect
 * - miss / 异常 → 走 introspect 兜底
 *
 * Phase A 真实落地用 Redis adapter，本接口允许在 wiring 时替换。本模块只提供
 * [NoopSessionVersionCache] 和 [InMemorySessionVersionCache]（测试用）。
 *
 * 实现**必须**：
 * - cache 不可用时 [get] 返回 null（不抛异常）；让 caller fallback 到 introspect
 * - [set] 异常应被吞掉；写不进去就当无 cache
 */
public interface SessionVersionCache {
    public suspend fun get(uid: Long, deviceId: String): Long?
    public suspend fun set(uid: Long, deviceId: String, version: Long, ttlSeconds: Long)
}

/**
 * 全空实现：每次 [get] 都返 null，每次 [set] 都丢弃。
 *
 * 用于 phase A flag `enabled=false` 路径下不引入任何 cache 依赖；以及单测中
 * 需要"不走 cache，全部 introspect"的场景。
 */
public object NoopSessionVersionCache : SessionVersionCache {
    override suspend fun get(uid: Long, deviceId: String): Long? = null
    override suspend fun set(uid: Long, deviceId: String, version: Long, ttlSeconds: Long) {}
}

/**
 * 进程内 cache（带 TTL 过期），主要给单测和本机 dev 调试用。
 *
 * 不是 thread-safe 之外的并发安全保证 —— 实际生产请用 Redis adapter（[SessionVersionCache]
 * 的另一实现），本类不在生产路径。
 */
public class InMemorySessionVersionCache(
    private val clock: Clock = Clock.System,
) : SessionVersionCache {
    private data class Entry(val version: Long, val expiresAtMillis: Long)

    private val map = HashMap<String, Entry>()
    private val mutex = Mutex()

    private fun keyOf(uid: Long, deviceId: String): String = "$uid:$deviceId"

    override suspend fun get(uid: Long, deviceId: String): Long? = mutex.withLock {
        val now = clock.now().toEpochMilliseconds()
        val e = map[keyOf(uid, deviceId)] ?: return null
        if (now >= e.expiresAtMillis) {
            map.remove(keyOf(uid, deviceId))
            null
        } else {
            e.version
        }
    }

    override suspend fun set(
        uid: Long,
        deviceId: String,
        version: Long,
        ttlSeconds: Long,
    ): Unit = mutex.withLock {
        val expiresAt = clock.now().toEpochMilliseconds() + ttlSeconds * 1000
        map[keyOf(uid, deviceId)] = Entry(version, expiresAt)
    }

    public suspend fun size(): Int = mutex.withLock { map.size }
}
