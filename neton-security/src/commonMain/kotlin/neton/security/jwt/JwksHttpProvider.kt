package neton.security.jwt

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

/**
 * JWKS 缓存 + unknown-kid 同步 refresh + DoS 节流（spec TOKEN_UNIFICATION_SPEC v1.3 §11.1 / §7.3）。
 *
 * 行为契约：
 * - 内存 cache，TTL 默认 1h（[cacheTtlMillis]）
 * - [resolve] 收到未命中的 kid → 强制 refresh 一次 JWKS → 仍未命中返 null
 * - **DoS 节流**：unknown kid 强制 refresh 全局共享一个 [refreshThrottleMillis] 窗口；
 *   同一窗口内任何 unknown kid 请求都跳过远程拉取（但仍走 cache 二次查询，因为
 *   背后可能已经被另一个并发请求刷新了）
 * - cache miss → 启动一次远程 fetch；并发情况下用 mutex 收敛成一次实际 fetch
 *
 * **不**做：
 * - 异步后台预热：spec §11.1 步骤 4 要求 unknown kid 必须**同步**强制 refresh 一次
 *   再决定（async 后台拉会让 rotation 第一批请求被错误 401）
 *
 * 实例化时不会立刻拉 JWKS；首次 [resolve] 才触发。
 */
public class JwksHttpProvider(
    private val fetcher: JwksFetcher,
    private val clock: Clock = Clock.System,
    private val cacheTtlMillis: Long = DEFAULT_CACHE_TTL_MILLIS,
    private val refreshThrottleMillis: Long = DEFAULT_REFRESH_THROTTLE_MILLIS,
) : JwksKeyProvider {
    private val mutex = Mutex()

    /** 当前缓存的所有 kid。 */
    private var cache: Map<String, JwkRsaMaterial> = emptyMap()

    /** 是否曾经成功加载过 cache。`false` 时 [resolve] 总是先 fetch（绕过 TTL 判断）。 */
    private var everLoaded: Boolean = false

    /** 缓存的入库时间（Unix ms），仅在 [everLoaded] 为 true 时有意义。 */
    private var cacheLoadedAtMillis: Long = 0L

    /** 是否曾经发生过 unknown-kid 强制 refresh。`false` 时不触发节流（首次允许）。 */
    private var everForcedRefresh: Boolean = false

    /** 上一次 unknown-kid 强制 refresh 的时间（Unix ms），节流用。 */
    private var lastForcedRefreshMillis: Long = 0L

    override suspend fun resolve(kid: String): JwkRsaMaterial? {
        // 1) cache 内查（即使过期也先查一次，快路径）
        cache[kid]?.let { return it }

        // 2) cache miss / 过期 / unknown kid → 同步刷新
        return mutex.withLock {
            val now = clock.now().toEpochMilliseconds()
            // 双重检查：可能其他 coroutine 刚好刷新过
            cache[kid]?.let { return@withLock it }

            val cacheExpired = !everLoaded || (now - cacheLoadedAtMillis) >= cacheTtlMillis
            val unknownKid = kid !in cache.keys
            val canForcedRefresh = unknownKid &&
                (!everForcedRefresh || (now - lastForcedRefreshMillis) >= refreshThrottleMillis)

            if (cacheExpired || canForcedRefresh) {
                refreshUnsafe(now, isForced = unknownKid && canForcedRefresh && everLoaded)
            }
            // 刷新后再查；仍找不到就是真不存在
            cache[kid]
        }
    }

    /**
     * 强制刷新（绕过 throttle）。仅给单测用。
     */
    public suspend fun forceRefresh() {
        mutex.withLock {
            refreshUnsafe(clock.now().toEpochMilliseconds(), isForced = true)
        }
    }

    /**
     * 当前 cache 中的 kid 列表（监控 / 单测）。
     */
    public suspend fun cachedKids(): List<String> = mutex.withLock { cache.keys.toList() }

    /**
     * 外部 fetcher 返回 list；写进 cache + 更新时间戳。Caller 必须持有 [mutex]。
     */
    private suspend fun refreshUnsafe(nowMillis: Long, isForced: Boolean) {
        val keys = try {
            fetcher.fetch()
        } catch (_: Throwable) {
            // 拉不到时不破坏既有 cache；记 forced 时间防止循环重试
            if (isForced) {
                lastForcedRefreshMillis = nowMillis
                everForcedRefresh = true
            }
            return
        }
        cache = keys.associateBy { it.kid }
        cacheLoadedAtMillis = nowMillis
        everLoaded = true
        if (isForced) {
            lastForcedRefreshMillis = nowMillis
            everForcedRefresh = true
        }
    }

    public companion object {
        public const val DEFAULT_CACHE_TTL_MILLIS: Long = 3600_000L          // 1h
        public const val DEFAULT_REFRESH_THROTTLE_MILLIS: Long = 30_000L      // 30s
    }
}
