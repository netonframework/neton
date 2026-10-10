package neton.security.crypto

import kotlin.test.*

class TotpTest {
    @Test fun rfc6238Sha1Vectors() {
        val secret = "12345678901234567890".encodeToByteArray()
        for ((time, code) in listOf(59L to "94287082", 1111111109L to "07081804", 1111111111L to "14050471", 1234567890L to "89005924", 2000000000L to "69279037", 20000000000L to "65353130")) {
            assertEquals(code, Totp.code(secret, time / 30, 8))
        }
        assertContentEquals(secret, Totp.decode(Totp.base32(secret)))
    }
    @Test fun replayAndInputValidation() {
        val secret = Totp.base32("12345678901234567890".encodeToByteArray())
        assertEquals(1L, Totp.match(secret, "287082", 59, -1))
        assertNull(Totp.match(secret, "287082", 59, 1))
        assertNull(Totp.match(secret, "287082", 180, -1))
        assertNull(Totp.match(secret, "abcdef", 59, -1))
    }
}
