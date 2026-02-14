package neton.security

import neton.security.identity.AuthenticationException
import neton.security.identity.IdentityUser
import neton.security.identity.UserId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Identity v1.1 契约测试
 *
 * @see Neton-Security-Spec-v1.1-API-Freeze.md 第十节
 */
class SecurityIdentityContractTest {

    @Test
    fun userIdParse_invalidString_throwsAuthenticationException() {
        val ex = kotlin.runCatching { UserId.parse("abc") }.exceptionOrNull()
            as? AuthenticationException ?: error("Expected AuthenticationException")
        assertEquals("InvalidUserId", ex.code)
        assertEquals("Invalid user id", ex.message)
        assertEquals("sub", ex.path)
    }

    @Test
    fun userIdParse_overflowULong_throwsAuthenticationException() {
        val ex = kotlin.runCatching { UserId.parse("18446744073709551616") }.exceptionOrNull()
            as? AuthenticationException ?: error("Expected AuthenticationException")
        assertEquals("InvalidUserId", ex.code)
    }

    @Test
    fun userIdParse_validString_returnsUserId() {
        val id = UserId.parse("123")
        assertEquals(123UL, id.value)
    }

    @Test
    fun identityUser_hasRole_isCaseSensitive() {
        val user = IdentityUser(UserId(1UL), setOf("Admin"), setOf("user:read"))
        assertTrue(user.hasRole("Admin"))
        assertFalse(user.hasRole("admin"))
    }

    @Test
    fun identityUser_hasPermission_isCaseSensitive() {
        val user = IdentityUser(UserId(1UL), emptySet(), setOf("user:read"))
        assertTrue(user.hasPermission("user:read"))
        assertFalse(user.hasPermission("User:Read"))
    }
}
