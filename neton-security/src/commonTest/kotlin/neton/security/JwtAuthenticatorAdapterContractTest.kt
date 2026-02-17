package neton.security

import kotlinx.coroutines.runBlocking
import neton.core.interfaces.Identity
import neton.core.interfaces.RequestContext
import neton.security.identity.UserId
import neton.security.jwt.JwtAuthenticatorV1
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * JwtAuthenticatorAdapter 委托契约测试（beta1 冻结）
 *
 * 冻结规则：JwtAuthenticatorAdapter 必须正确委托 JwtAuthenticatorV1，
 * 不能是空实现（return null）。
 *
 * 防回潮：签发 → 认证 round-trip 必须返回非 null Identity，
 * 且 userId / roles / permissions 完整透传。
 */
class JwtAuthenticatorAdapterContractTest {

    private val secret = "beta1-contract-test-secret"
    private val realAuth = JwtAuthenticatorAdapter(secret)
    private val jwt = JwtAuthenticatorV1(secret)

    private fun coreCtx(headers: Map<String, String>) = object : RequestContext {
        override val path = "/"
        override val method = "GET"
        override val headers = headers
        override val routeGroup: String? = null
    }

    // --- Test 1: round-trip — createToken → authenticate 返回非 null ---
    @Test
    fun roundTrip_createAndAuthenticate_returnsIdentity() = runBlocking {
        val userId = UserId.parse("42")
        val roles = setOf("admin")
        val perms = setOf("system:user:page", "system:user:create")

        val token = jwt.createToken(userId, roles, perms)
        val identity = realAuth.authenticate(coreCtx(mapOf("Authorization" to "Bearer $token")))

        assertNotNull(identity, "JwtAuthenticatorAdapter must NOT return null for valid token")
        assertEquals("42", identity.id)
        assertEquals(roles, identity.roles)
        assertEquals(perms, identity.permissions)
    }

    // --- Test 2: 无 Authorization header → null（非 throw） ---
    @Test
    fun noAuthHeader_returnsNull() = runBlocking {
        val result = realAuth.authenticate(coreCtx(emptyMap()))
        assertNull(result)
    }

    // --- Test 3: 无效 token → null（异常被吞，不向外抛） ---
    @Test
    fun invalidToken_returnsNull_doesNotThrow() = runBlocking {
        val result = realAuth.authenticate(coreCtx(mapOf("Authorization" to "Bearer invalid.token.here")))
        assertNull(result)
    }

    // --- Test 4: 过期 token → null ---
    @Test
    fun expiredToken_returnsNull() = runBlocking {
        val userId = UserId.parse("1")
        val token = jwt.createToken(userId, expiresInSeconds = 0)
        val result = realAuth.authenticate(coreCtx(mapOf("Authorization" to "Bearer $token")))
        assertNull(result)
    }

    // --- Test 5: Identity 实现了 hasPermission / hasRole ---
    @Test
    fun identity_hasPermission_hasRole_work() = runBlocking {
        val userId = UserId.parse("1")
        val token = jwt.createToken(userId, setOf("admin"), setOf("system:user:page"))
        val identity = realAuth.authenticate(coreCtx(mapOf("Authorization" to "Bearer $token")))

        assertNotNull(identity)
        assertTrue(identity.hasRole("admin"))
        assertTrue(identity.hasPermission("system:user:page"))
        assertTrue(!identity.hasRole("guest"))
        assertTrue(!identity.hasPermission("system:user:delete"))
    }

    // --- Test 6: name 属性为 "jwt" ---
    @Test
    fun authenticatorName_isJwt() {
        assertEquals("jwt", realAuth.name)
    }
}
