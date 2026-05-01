package neton.security.jwt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

/**
 * UnifiedTokenAuthenticator 单测（spec §7.3）。
 *
 * 覆盖：
 * 1. cache hit 同 version → 不调 introspect
 * 2. cache miss → introspect active=true → 写 cache → 放行
 * 3. cache mismatch → introspect → cache 真值更新
 * 4. cache get 抛异常 → 走 introspect 兜底
 * 5. introspect 抛异常 → fail closed
 */
class UnifiedTokenAuthenticatorTest {

    private fun newVerifier(): RsaJwtVerifier = RsaJwtVerifier(
        keyProvider = StaticJwksKeyProvider(
            mapOf(UnifiedTokenTestFixtures.KID_V1 to UnifiedTokenTestFixtures.DEFAULT_JWK),
        ),
    )

    @Test
    fun cache_hit_same_version_skips_introspect() = runTest {
        val cache = InMemorySessionVersionCache()
        cache.set(uid = 42, deviceId = "poc-dev", version = 1, ttlSeconds = 30)

        val introspector = CountingIntrospector { error("must not be called on cache hit") }
        val auth = UnifiedTokenAuthenticator(
            verifier = newVerifier(),
            introspector = introspector,
            sessionVersionCache = cache,
        )

        val result = auth.authenticate(UnifiedTokenTestFixtures.TOKEN_VALID)
        val ok = assertIs<AuthOutcome.Success>(result)
        assertEquals(42L, ok.identity.userId)
        assertEquals(0, introspector.callCount, "cache hit 必须不调 introspect")
    }

    @Test
    fun cache_miss_calls_introspect_then_caches() = runTest {
        val cache = InMemorySessionVersionCache()
        val introspector = CountingIntrospector {
            IntrospectionResult(
                active = true,
                userId = 42,
                deviceId = "poc-dev",
                sessionVersion = 1,
                scope = listOf("user"),
                expiresAt = 2_000_000_000,
                jti = "poc-jti",
            )
        }
        val auth = UnifiedTokenAuthenticator(
            verifier = newVerifier(),
            introspector = introspector,
            sessionVersionCache = cache,
        )

        val result = auth.authenticate(UnifiedTokenTestFixtures.TOKEN_VALID)
        assertIs<AuthOutcome.Success>(result)
        assertEquals(1, introspector.callCount, "cache miss 必须调 1 次 introspect")
        assertEquals(1L, cache.get(42, "poc-dev"), "成功后 cache 必须按真值写入")
    }

    @Test
    fun cache_mismatch_writes_authoritative_version_from_introspect() = runTest {
        val cache = InMemorySessionVersionCache()
        // cache 里旧 version = 99（虚构），token claim 里 sv=5；不一致 → 走 introspect
        cache.set(uid = 42, deviceId = "poc-dev", version = 99, ttlSeconds = 30)

        // server introspect 给出真值 sv=5（active）
        val introspector = CountingIntrospector {
            IntrospectionResult(
                active = true,
                userId = 42,
                deviceId = "poc-dev",
                sessionVersion = 5,
                scope = listOf("user"),
                expiresAt = 2_000_000_000,
                jti = "poc-jti",
            )
        }
        val auth = UnifiedTokenAuthenticator(
            verifier = newVerifier(),
            introspector = introspector,
            sessionVersionCache = cache,
        )

        val result = auth.authenticate(UnifiedTokenTestFixtures.TOKEN_SV5)
        val ok = assertIs<AuthOutcome.Success>(result)
        assertEquals(5L, ok.identity.sessionVersion, "应使用 introspect 的权威 sessionVersion")
        assertEquals(1, introspector.callCount)
        assertEquals(5L, cache.get(42, "poc-dev"), "cache 应被覆盖成 introspect 的权威值 5")
    }

    @Test
    fun cache_get_throws_falls_back_to_introspect() = runTest {
        val brokenCache = object : SessionVersionCache {
            override suspend fun get(uid: Long, deviceId: String): Long? {
                error("simulated redis outage")
            }

            override suspend fun set(uid: Long, deviceId: String, version: Long, ttlSeconds: Long) {
                // 写也假设失败，但本测试只验 get 异常路径
            }
        }
        val introspector = CountingIntrospector {
            IntrospectionResult(
                active = true,
                userId = 42,
                deviceId = "poc-dev",
                sessionVersion = 1,
                scope = listOf("user"),
                expiresAt = 2_000_000_000,
                jti = "poc-jti",
            )
        }
        val auth = UnifiedTokenAuthenticator(
            verifier = newVerifier(),
            introspector = introspector,
            sessionVersionCache = brokenCache,
        )

        val result = auth.authenticate(UnifiedTokenTestFixtures.TOKEN_VALID)
        assertIs<AuthOutcome.Success>(result)
        assertEquals(1, introspector.callCount, "cache 异常必须 fallback 到 introspect")
    }

    @Test
    fun introspect_throws_fails_closed() = runTest {
        val cache = NoopSessionVersionCache
        val throwingIntrospector = CountingIntrospector {
            error("simulated server outage")
        }
        val auth = UnifiedTokenAuthenticator(
            verifier = newVerifier(),
            introspector = throwingIntrospector,
            sessionVersionCache = cache,
        )

        val result = auth.authenticate(UnifiedTokenTestFixtures.TOKEN_VALID)
        val f = assertIs<AuthOutcome.Failure>(result)
        assertEquals(AuthFailureReason.IntrospectFailed, f.reason)
        assertEquals(1, throwingIntrospector.callCount)
    }

    @Test
    fun introspect_inactive_revoked_maps_to_Revoked_reason() = runTest {
        val introspector = CountingIntrospector {
            IntrospectionResult(active = false, reason = "revoked")
        }
        val auth = UnifiedTokenAuthenticator(
            verifier = newVerifier(),
            introspector = introspector,
            sessionVersionCache = NoopSessionVersionCache,
        )

        val result = auth.authenticate(UnifiedTokenTestFixtures.TOKEN_VALID)
        val f = assertIs<AuthOutcome.Failure>(result)
        assertEquals(AuthFailureReason.Revoked, f.reason)
    }

    @Test
    fun verify_failure_short_circuits_before_introspect() = runTest {
        val introspector = CountingIntrospector { error("must not be called when verify fails") }
        val auth = UnifiedTokenAuthenticator(
            verifier = newVerifier(),
            introspector = introspector,
            sessionVersionCache = NoopSessionVersionCache,
        )

        val result = auth.authenticate(UnifiedTokenTestFixtures.TOKEN_EXPIRED)
        val f = assertIs<AuthOutcome.Failure>(result)
        assertEquals(AuthFailureReason.Expired, f.reason)
        assertEquals(0, introspector.callCount, "verify 失败必须短路，不调 introspect")
    }

    @Test
    fun blank_token_returns_Malformed() = runTest {
        val auth = UnifiedTokenAuthenticator(
            verifier = newVerifier(),
            introspector = CountingIntrospector { error("not reached") },
            sessionVersionCache = NoopSessionVersionCache,
        )
        val result = auth.authenticate("   ")
        val f = assertIs<AuthOutcome.Failure>(result)
        assertEquals(AuthFailureReason.Malformed, f.reason)
    }

    // ─────────────────── helper ───────────────────

    private class CountingIntrospector(
        private val handler: () -> IntrospectionResult,
    ) : UnifiedTokenIntrospector {
        var callCount: Int = 0
            private set

        override suspend fun introspect(token: String): IntrospectionResult {
            callCount += 1
            return handler()
        }
    }
}
