package neton.security

import kotlinx.coroutines.runBlocking
import kotlin.io.encoding.Base64
import neton.security.identity.AuthenticationException
import neton.security.identity.IdentityUser
import neton.security.identity.UserId
import neton.security.internal.HmacSha256
import neton.security.jwt.JwtAuthenticatorV1
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * JWT v1 契约测试，锁死 5 个错误码及 code/path/message
 *
 * @see Neton-JWT-Authenticator-Spec-v1.md 第七节、第九节
 */
class JwtAuthenticatorContractTest {

    private val secret = "test-secret-key"
    private val jwt = JwtAuthenticatorV1(secret)

    private fun ctx(headers: Map<String, String>) = object : RequestContext {
        override val path = "/"
        override val method = "GET"
        override val headers = headers
        override val routeGroup: String? = null
        override fun getQueryParameter(name: String): String? = null
        override fun getQueryParameters(): Map<String, List<String>> = emptyMap()
        override suspend fun getBodyAsString(): String? = null
        override fun getSessionId(): String? = null
        override fun getRemoteAddress(): String? = null
    }

    private fun base64UrlEncode(s: String): String {
        val bytes = s.encodeToByteArray()
        return Base64.UrlSafe.encode(bytes).replace("=", "")
    }

    private fun buildToken(header: String, payload: String, signature: ByteArray): String {
        val h = base64UrlEncode(header)
        val p = base64UrlEncode(payload)
        val sig = Base64.UrlSafe.encode(signature).replace("=", "")
        return "$h.$p.$sig"
    }

    private fun validToken(payload: String): String {
        val header = """{"alg":"HS256"}"""
        val signingInput = "${base64UrlEncode(header)}.${base64UrlEncode(payload)}"
        val sig = HmacSha256.sign(secret.encodeToByteArray(), signingInput.encodeToByteArray())
        return buildToken(header, payload, sig)
    }

    // 1. Bearer 空 token → MissingToken path=Authorization
    @Test
    fun bearerEmptyToken_throwsMissingToken() = runBlocking {
        val ex = kotlin.runCatching {
            jwt.authenticate(ctx(mapOf("Authorization" to "Bearer ")))
        }.exceptionOrNull() as? AuthenticationException ?: error("Expected AuthenticationException")
        assertEquals("MissingToken", ex.code)
        assertEquals("Missing or invalid Bearer token", ex.message)
        assertEquals("Authorization", ex.path)
    }

    // 2. sub 非法 → InvalidUserId path=sub
    @Test
    fun invalidSub_throwsInvalidUserId() = runBlocking {
        val payload = """{"sub":"abc","exp":9999999999}"""
        val token = validToken(payload)
        val ex = kotlin.runCatching {
            jwt.authenticate(ctx(mapOf("Authorization" to "Bearer $token")))
        }.exceptionOrNull() as? AuthenticationException ?: error("Expected AuthenticationException")
        assertEquals("InvalidUserId", ex.code)
        assertEquals("Invalid user id", ex.message)
        assertEquals("sub", ex.path)
    }

    // 3. exp 过期 → TokenExpired path=exp
    @Test
    fun expiredToken_throwsTokenExpired() = runBlocking {
        val payload = """{"sub":"123","exp":0}"""
        val token = validToken(payload)
        val ex = kotlin.runCatching {
            jwt.authenticate(ctx(mapOf("Authorization" to "Bearer $token")))
        }.exceptionOrNull() as? AuthenticationException ?: error("Expected AuthenticationException")
        assertEquals("TokenExpired", ex.code)
        assertEquals("Token has expired", ex.message)
        assertEquals("exp", ex.path)
    }

    // 4. alg != HS256 → InvalidAlgorithm path=alg
    @Test
    fun wrongAlg_throwsInvalidAlgorithm() = runBlocking {
        val header = """{"alg":"hs256"}"""
        val payload = """{"sub":"123","exp":9999999999}"""
        val token = buildToken(header, payload, ByteArray(32) { it.toByte() })
        val ex = kotlin.runCatching {
            jwt.authenticate(ctx(mapOf("Authorization" to "Bearer $token")))
        }.exceptionOrNull() as? AuthenticationException ?: error("Expected AuthenticationException")
        assertEquals("InvalidAlgorithm", ex.code)
        assertEquals("Unsupported algorithm", ex.message)
        assertEquals("alg", ex.path)
    }

    // 5. 签名错误 → InvalidSignature path=""
    @Test
    fun wrongSignature_throwsInvalidSignature() = runBlocking {
        val header = """{"alg":"HS256"}"""
        val payload = """{"sub":"123","exp":9999999999}"""
        val token = buildToken(header, payload, ByteArray(32) { 0xff.toByte() })
        val ex = kotlin.runCatching {
            jwt.authenticate(ctx(mapOf("Authorization" to "Bearer $token")))
        }.exceptionOrNull() as? AuthenticationException ?: error("Expected AuthenticationException")
        assertEquals("InvalidSignature", ex.code)
        assertEquals("Invalid signature", ex.message)
        assertEquals("", ex.path)
    }

    // 6. roles/perms 缺失 → emptySet（不 throw）
    @Test
    fun missingRolesPerms_returnsEmptySet() = runBlocking {
        val payload = """{"sub":"123","exp":9999999999}"""
        val token = validToken(payload)
        val user = jwt.authenticate(ctx(mapOf("Authorization" to "Bearer $token")))
            ?: error("Expected IdentityUser")
        assertTrue(user is IdentityUser)
        assertEquals(emptySet<String>(), user.roles)
        assertEquals(emptySet<String>(), user.permissions)
    }

    // 7. Authorization header case-insensitive（小写 header 也能识别）
    @Test
    fun authorizationHeaderCaseInsensitive_acceptsLowerCase() = runBlocking {
        val payload = """{"sub":"123","exp":9999999999}"""
        val token = validToken(payload)
        val user = jwt.authenticate(ctx(mapOf("authorization" to "Bearer $token")))
            ?: error("Expected IdentityUser")
        assertEquals(UserId.parse("123").value, user.id.value)
    }

    // 8. 无 Authorization → null
    @Test
    fun noAuthorization_returnsNull() = runBlocking {
        val result = jwt.authenticate(ctx(emptyMap()))
        assertNull(result)
    }
}
