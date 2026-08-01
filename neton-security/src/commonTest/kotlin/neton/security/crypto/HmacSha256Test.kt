package neton.security.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HmacSha256Test {

    @Test
    fun signHexAndVerifyHexRoundTrip() {
        val secret = "platform-secret".encodeToByteArray()
        val payload = "appId=demo&timestamp=1710000000".encodeToByteArray()

        val signature = HmacSha256.signHex(secret, payload)

        assertEquals(64, signature.length)
        assertTrue(HmacSha256.verifyHex(secret, payload, signature))
    }

    @Test
    fun verifyHexRejectsInvalidHexAndModifiedSignature() {
        val secret = "platform-secret".encodeToByteArray()
        val payload = "appId=demo&timestamp=1710000000".encodeToByteArray()
        val signature = HmacSha256.signHex(secret, payload)
        val tampered = signature.replaceRange(0, 2, "00")

        assertFalse(HmacSha256.verifyHex(secret, payload, "not-hex"))
        assertFalse(HmacSha256.verifyHex(secret, payload, tampered))
    }
}
