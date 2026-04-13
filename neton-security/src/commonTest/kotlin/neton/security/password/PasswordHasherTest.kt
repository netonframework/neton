package neton.security.password

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PasswordHasherTest {

    @Test
    fun hashAndVerifyCurrentFormat() {
        val hash = PasswordHasher.hash("CorrectHorseBatteryStaple!")

        val result = PasswordHasher.verify("CorrectHorseBatteryStaple!", hash)

        assertTrue(result.verified)
        assertFalse(result.needsRehash)
    }

    @Test
    fun differentHashesForSamePassword() {
        val first = PasswordHasher.hash("same-password")
        val second = PasswordHasher.hash("same-password")

        assertNotEquals(first, second)
        assertTrue(PasswordHasher.verify("same-password", first).verified)
        assertTrue(PasswordHasher.verify("same-password", second).verified)
    }

    @Test
    fun verifyLegacyHashAndMarkForRehash() {
        val legacyHash = "dcf97c4b4415986cad61148a408e87b17968bfe4904cda4d64b5e17064d1a0f8"

        val result = PasswordHasher.verify("admin123", legacyHash)

        assertTrue(result.verified)
        assertTrue(result.needsRehash)
    }
}
