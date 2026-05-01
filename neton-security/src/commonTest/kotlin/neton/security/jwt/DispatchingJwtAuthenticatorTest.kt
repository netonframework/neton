package neton.security.jwt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import neton.core.interfaces.Identity
import neton.security.Authenticator
import neton.security.DefaultRequestContext
import neton.security.RequestContext
import neton.security.identity.IdentityUser
import neton.security.identity.UserId

/**
 * DispatchingJwtAuthenticator 单测（spec TOKEN_UNIFICATION_SPEC v1.3 Phase A）。
 *
 * 覆盖 4 种 flag 组合的路由行为 + fail-fast + iss-based 路由 + Bearer 前缀。
 */
class DispatchingJwtAuthenticatorTest {

    private fun newVerifier(): RsaJwtVerifier = RsaJwtVerifier(
        keyProvider = StaticJwksKeyProvider(
            mapOf(UnifiedTokenTestFixtures.KID_V1 to UnifiedTokenTestFixtures.DEFAULT_JWK),
        ),
    )

    private fun newUnifiedAuth(): UnifiedTokenAuthenticator = UnifiedTokenAuthenticator(
        verifier = newVerifier(),
        introspector = AlwaysActiveIntrospector(),
        sessionVersionCache = NoopSessionVersionCache,
    )

    private val LEGACY_IDENTITY: Identity = IdentityUser(
        userId = UserId(99u),
        roles = setOf("legacy"),
        permissions = emptySet(),
    )

    private fun ctxFor(token: String?): RequestContext = DefaultRequestContext(
        method = "GET",
        path = "/test",
        headers = if (token != null) mapOf("Authorization" to "Bearer $token") else emptyMap(),
    )

    // ─────────────── flag combinations ───────────────

    @Test
    fun phase_a_default_legacy_token_goes_to_legacy() = runTest {
        val legacy = StubAuthenticator { LEGACY_IDENTITY }
        val d = DispatchingJwtAuthenticator(
            legacy = legacy,
            unified = newUnifiedAuth(),
            unifiedEnabled = false,
            verifyLegacyJwt = true,
        )
        // 一个看起来像 legacy 的 token：`iss` 不是 privchat-server（这里用 valid token 但 dispatcher
        // 只看 iss；用 fixture 中 wrong_iss 的 token 当 legacy 路由 token 即可）
        val r = d.authenticate(ctxFor(UnifiedTokenTestFixtures.TOKEN_WRONG_ISS))
        assertEquals(LEGACY_IDENTITY, r)
        assertEquals(1, legacy.callCount)
    }

    @Test
    fun phase_a_default_unified_token_is_rejected_not_falling_back_to_legacy() = runTest {
        val legacy = StubAuthenticator { error("must not be called for unified-iss tokens") }
        val d = DispatchingJwtAuthenticator(
            legacy = legacy,
            unified = newUnifiedAuth(),
            unifiedEnabled = false,
            verifyLegacyJwt = true,
        )
        val r = d.authenticate(ctxFor(UnifiedTokenTestFixtures.TOKEN_VALID))
        assertNull(r, "unified token 在 enabled=false 时必须被拒，且不能 fallback 到 legacy")
        assertEquals(0, legacy.callCount, "legacy 不应被调用")
    }

    @Test
    fun phase_b_dual_verify_unified_token_goes_to_unified() = runTest {
        val legacy = StubAuthenticator { error("legacy must not run for unified-iss tokens") }
        val d = DispatchingJwtAuthenticator(
            legacy = legacy,
            unified = newUnifiedAuth(),
            unifiedEnabled = true,
            verifyLegacyJwt = true,
        )
        val r = d.authenticate(ctxFor(UnifiedTokenTestFixtures.TOKEN_VALID))
        assertNotNull(r)
        // dispatcher 把 unified token 映射成 IdentityUser；userId 来自 sub=42
        val asUser = r as IdentityUser
        assertEquals(UserId(42u), asUser.userId)
    }

    @Test
    fun phase_b_dual_verify_legacy_token_goes_to_legacy() = runTest {
        val legacy = StubAuthenticator { LEGACY_IDENTITY }
        val d = DispatchingJwtAuthenticator(
            legacy = legacy,
            unified = newUnifiedAuth(),
            unifiedEnabled = true,
            verifyLegacyJwt = true,
        )
        val r = d.authenticate(ctxFor(UnifiedTokenTestFixtures.TOKEN_WRONG_ISS))
        assertEquals(LEGACY_IDENTITY, r)
        assertEquals(1, legacy.callCount)
    }

    @Test
    fun phase_c_legacy_off_legacy_token_returns_null() = runTest {
        val legacy = StubAuthenticator { error("legacy off; must not be called") }
        val d = DispatchingJwtAuthenticator(
            legacy = legacy,
            unified = newUnifiedAuth(),
            unifiedEnabled = true,
            verifyLegacyJwt = false,
        )
        val r = d.authenticate(ctxFor(UnifiedTokenTestFixtures.TOKEN_WRONG_ISS))
        assertNull(r, "verify_legacy_jwt=false 时 legacy token 必须返 null")
        assertEquals(0, legacy.callCount)
    }

    @Test
    fun fail_fast_when_both_disabled() {
        // enabled=false + verifyLegacyJwt=false → 任何请求都会 401，等于服务下线
        val ex = assertFailsWith<IllegalArgumentException> {
            DispatchingJwtAuthenticator(
                legacy = StubAuthenticator { null },
                unified = null,
                unifiedEnabled = false,
                verifyLegacyJwt = false,
            )
        }
        // require 抛 IllegalArgumentException；message 必须提示 "401 every request"
        val msg = ex.message.orEmpty()
        check("401" in msg || "verify_legacy_jwt" in msg) {
            "fail-fast message 应该明确指出 misconfiguration: $msg"
        }
    }

    @Test
    fun fail_fast_when_enabled_but_no_unified_authenticator() {
        assertFailsWith<IllegalArgumentException> {
            DispatchingJwtAuthenticator(
                legacy = StubAuthenticator { null },
                unified = null,
                unifiedEnabled = true,
                verifyLegacyJwt = true,
            )
        }
    }

    // ─────────────── header parsing ───────────────

    @Test
    fun missing_authorization_header_returns_null() = runTest {
        val legacy = StubAuthenticator { error("must not be called when no Authorization header") }
        val d = DispatchingJwtAuthenticator(
            legacy = legacy,
            unified = newUnifiedAuth(),
            unifiedEnabled = true,
            verifyLegacyJwt = true,
        )
        val r = d.authenticate(ctxFor(token = null))
        assertNull(r)
        assertEquals(0, legacy.callCount)
    }

    @Test
    fun non_bearer_header_returns_null() = runTest {
        val legacy = StubAuthenticator { error("must not be called for non-Bearer header") }
        val d = DispatchingJwtAuthenticator(
            legacy = legacy,
            unified = newUnifiedAuth(),
            unifiedEnabled = true,
            verifyLegacyJwt = true,
        )
        val ctx = DefaultRequestContext(
            method = "GET",
            path = "/test",
            headers = mapOf("Authorization" to "Basic dXNlcjpwYXNz"),
        )
        val r = d.authenticate(ctx)
        assertNull(r)
        assertEquals(0, legacy.callCount)
    }

    // ─────────────── peekIssOrNull edge cases ───────────────

    @Test
    fun peek_iss_returns_null_for_malformed() {
        assertNull(DispatchingJwtAuthenticator.peekIssOrNull("not.a.jwt"))
        assertNull(DispatchingJwtAuthenticator.peekIssOrNull(""))
        assertNull(DispatchingJwtAuthenticator.peekIssOrNull("only-one-segment"))
    }

    @Test
    fun peek_iss_extracts_privchat_server_for_unified_token() {
        val iss = DispatchingJwtAuthenticator.peekIssOrNull(UnifiedTokenTestFixtures.TOKEN_VALID)
        assertEquals("privchat-server", iss)
    }

    @Test
    fun peek_iss_extracts_other_for_non_unified_token() {
        val iss = DispatchingJwtAuthenticator.peekIssOrNull(UnifiedTokenTestFixtures.TOKEN_WRONG_ISS)
        assertEquals("malicious-issuer", iss)
    }

    // ─────────────── helpers ───────────────

    private class StubAuthenticator(
        private val handler: () -> Identity?,
    ) : Authenticator {
        override val name: String = "stub-legacy"
        var callCount: Int = 0
            private set

        override suspend fun authenticate(context: RequestContext): Identity? {
            callCount += 1
            return handler()
        }
    }

    private class AlwaysActiveIntrospector : UnifiedTokenIntrospector {
        override suspend fun introspect(token: String): IntrospectionResult =
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
}
