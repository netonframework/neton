package neton.security.jwt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

/**
 * JwksHttpProvider 单测（spec §11.1：unknown kid 同步 refresh + retry once + 节流）。
 */
class JwksHttpProviderTest {

    private val k1 = JwkRsaMaterial(kid = "v1", n = "n1", e = "AQAB")

    @Test
    fun cache_hit_does_not_call_fetcher_twice() = runTest {
        val fetcher = CountingJwksFetcher(listOf(k1))
        val clock = AdvanceableClock(0)
        val provider = JwksHttpProvider(fetcher = fetcher, clock = clock)

        assertEquals(k1, provider.resolve("v1"))
        assertEquals(1, fetcher.fetchCount)

        // 第二次 resolve 同一个 kid，cache 命中，不应再 fetch
        assertEquals(k1, provider.resolve("v1"))
        assertEquals(1, fetcher.fetchCount, "cache hit must NOT call fetcher again")
    }

    @Test
    fun unknown_kid_force_refresh_once_and_succeeds_on_retry() = runTest {
        // 第一次 fetch 只返 v1；运营轮换后 fetcher 改返 v1+v2
        val mutableFetcher = MutableKeysFetcher(listOf(k1))
        val clock = AdvanceableClock(0)
        val provider = JwksHttpProvider(fetcher = mutableFetcher, clock = clock)

        // 先把 v1 写进 cache（也确认初始状态）
        assertEquals(k1, provider.resolve("v1"))
        val countAfterInit = mutableFetcher.fetchCount

        // server 出新 key v2；此时 cache 还不知道
        val k2 = JwkRsaMaterial(kid = "v2", n = "n2", e = "AQAB")
        mutableFetcher.replace(listOf(k1, k2))

        // resolve unknown kid → 强制 refresh → 成功
        val resolved = provider.resolve("v2")
        assertNotNull(resolved, "unknown kid 后强制 refresh 应能找到 v2")
        assertEquals(k2, resolved)
        assertEquals(countAfterInit + 1, mutableFetcher.fetchCount)
    }

    @Test
    fun forced_refresh_throttle_blocks_second_attempt_within_30s() = runTest {
        val mutableFetcher = MutableKeysFetcher(listOf(k1))
        val clock = AdvanceableClock(0)
        val provider = JwksHttpProvider(fetcher = mutableFetcher, clock = clock)

        // 触发首次 fetch（cache empty → load）
        assertEquals(k1, provider.resolve("v1"))
        val baseline = mutableFetcher.fetchCount

        // 第一次 unknown kid：throttle 允许 → fetch 一次
        assertNull(provider.resolve("attacker-kid-1"))
        assertEquals(baseline + 1, mutableFetcher.fetchCount)

        // 同窗口（< 30s）再来 unknown kid → throttle 拦截
        clock.advanceMillis(10_000) // +10s（仍在 30s 内）
        assertNull(provider.resolve("attacker-kid-2"))
        assertEquals(baseline + 1, mutableFetcher.fetchCount, "30s 窗口内 unknown kid 必须不再 fetch")

        // 越过 30s + 1ms → 解封 throttle，再 unknown kid 允许 fetch
        clock.advanceMillis(20_001) // 总 +30.001s
        assertNull(provider.resolve("attacker-kid-3"))
        assertEquals(baseline + 2, mutableFetcher.fetchCount, "throttle 过期后允许再 fetch 一次")
    }

    // ───────────── helpers ─────────────

    /**
     * 可前进时间的 Clock；KMP commonTest 跨 native target 都能用。
     */
    private class AdvanceableClock(initialMillis: Long) : Clock {
        private var now = initialMillis
        fun advanceMillis(millis: Long) {
            now += millis
        }

        override fun now(): Instant = Instant.fromEpochMilliseconds(now)
    }

    /** 可在测试中替换 keys 列表的 fetcher。 */
    private class MutableKeysFetcher(initial: List<JwkRsaMaterial>) : JwksFetcher {
        private var keys: List<JwkRsaMaterial> = initial
        var fetchCount: Int = 0
            private set

        fun replace(new: List<JwkRsaMaterial>) {
            keys = new
        }

        override suspend fun fetch(): List<JwkRsaMaterial> {
            fetchCount += 1
            return keys
        }
    }
}
