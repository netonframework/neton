package neton.security.jwt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest

/**
 * RsaJwtVerifier 单测（spec TOKEN_UNIFICATION_SPEC v1.3 §6.1）。
 *
 * fixture 见 [UnifiedTokenTestFixtures]：所有 token 用 server `.local/keys/jwt/v1.pem` 离线签出来。
 */
class RsaJwtVerifierTest {

    private fun newVerifier(): RsaJwtVerifier = RsaJwtVerifier(
        keyProvider = StaticJwksKeyProvider(
            mapOf(UnifiedTokenTestFixtures.KID_V1 to UnifiedTokenTestFixtures.DEFAULT_JWK),
        ),
    )

    @Test
    fun valid_token_returns_full_claims() = runTest {
        val r = newVerifier().verify(UnifiedTokenTestFixtures.TOKEN_VALID)
        val s = assertIs<VerifyResult.Success>(r)
        assertEquals("privchat-server", s.claims.issuer)
        assertEquals(42L, s.claims.userId)
        assertEquals("poc-dev", s.claims.deviceId)
        assertEquals(1L, s.claims.sessionVersion)
        assertEquals(listOf("user"), s.claims.scope)
        assertEquals(
            listOf("privchat-application", "privchat-server"),
            s.claims.audience,
        )
        assertEquals(2_000_000_000L, s.claims.expiresAt)
        assertEquals("poc-jti", s.claims.jti)
        assertEquals("v1", s.claims.kid)
        assertEquals("access", s.claims.tokenType)
    }

    @Test
    fun unknown_kid_returns_UnknownKid() = runTest {
        val r = newVerifier().verify(UnifiedTokenTestFixtures.TOKEN_UNKNOWN_KID)
        val f = assertIs<VerifyResult.Failure>(r)
        assertEquals(VerifyError.UnknownKid, f.error)
    }

    @Test
    fun tampered_signature_returns_SignatureInvalid() = runTest {
        // 把最后一字符换掉，破坏签名
        val src = UnifiedTokenTestFixtures.TOKEN_VALID
        val tampered = src.dropLast(1) + if (src.last() == 'A') 'B' else 'A'
        val r = newVerifier().verify(tampered)
        val f = assertIs<VerifyResult.Failure>(r)
        assertEquals(VerifyError.SignatureInvalid, f.error)
    }

    @Test
    fun expired_token_returns_Expired() = runTest {
        val r = newVerifier().verify(UnifiedTokenTestFixtures.TOKEN_EXPIRED)
        val f = assertIs<VerifyResult.Failure>(r)
        assertEquals(VerifyError.Expired, f.error)
    }

    @Test
    fun wrong_issuer_returns_IssuerMismatch() = runTest {
        val r = newVerifier().verify(UnifiedTokenTestFixtures.TOKEN_WRONG_ISS)
        val f = assertIs<VerifyResult.Failure>(r)
        assertEquals(VerifyError.IssuerMismatch, f.error)
    }

    @Test
    fun missing_required_audience_returns_AudienceMismatch() = runTest {
        // aud=["privchat-server"]，缺 privchat-application
        val r = newVerifier().verify(UnifiedTokenTestFixtures.TOKEN_WRONG_AUD)
        val f = assertIs<VerifyResult.Failure>(r)
        assertEquals(VerifyError.AudienceMismatch, f.error)
    }

    @Test
    fun typ_refresh_returns_InvalidType_when_expecting_access() = runTest {
        val r = newVerifier().verify(UnifiedTokenTestFixtures.TOKEN_TYP_REFRESH)
        val f = assertIs<VerifyResult.Failure>(r)
        assertEquals(VerifyError.InvalidType, f.error)
    }

    @Test
    fun malformed_token_three_parts_required() = runTest {
        val r = newVerifier().verify("not.a.jwt.atall.toomanyparts")
        val f = assertIs<VerifyResult.Failure>(r)
        // "not.a.jwt.atall.toomanyparts" splits into 5 parts → Malformed
        assertEquals(VerifyError.Malformed, f.error)
    }
}
