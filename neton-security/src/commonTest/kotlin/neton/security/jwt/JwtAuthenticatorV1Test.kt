package neton.security.jwt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import neton.security.identity.AuthenticationException
import neton.security.identity.UserId

class JwtAuthenticatorV1Test {

    @Test
    fun verifyToken_returnsClaimsForRefreshValidation() {
        val authenticator = JwtAuthenticatorV1("test-secret")
        val token = authenticator.createToken(
            userId = UserId(1uL),
            expiresInSeconds = 3600,
            extraClaims = mapOf("type" to "refresh", "scope" to "member")
        )

        val verified = authenticator.verifyToken(token)

        assertEquals(UserId(1uL), verified.identity.userId)
        assertEquals("refresh", verified.claimString("type"))
        assertEquals("member", verified.claimString("scope"))
    }

    @Test
    fun verifyToken_rejectsTamperedSignature() {
        val authenticator = JwtAuthenticatorV1("test-secret")
        val token = authenticator.createToken(
            userId = UserId(1uL),
            expiresInSeconds = 3600,
            extraClaims = mapOf("type" to "refresh")
        )
        val tampered = token.substringBeforeLast('.') + ".AAAA"

        assertFailsWith<AuthenticationException> {
            authenticator.verifyToken(tampered)
        }
    }
}
