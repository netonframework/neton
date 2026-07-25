package neton.security.crypto

import neton.security.random.SecureRandom
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalEncodingApi::class)
class SecretBoxTest {

    private fun box() = SecretBox(SecureRandom.bytes(32))

    @Test fun roundtrips() {
        val b = box()
        val secret = "sk-live-abcdefghijklmnopqrstuvwxyz"
        assertEquals(secret, b.open(b.seal(secret)))
    }

    @Test fun ciphertext_does_not_contain_plaintext() {
        val secret = "sk-super-secret-value"
        val sealed = box().seal(secret)
        assertFalse(sealed.contains(secret), "明文不得出现在密文中：$sealed")
        assertTrue(sealed.startsWith("enc:v1:"))
    }

    @Test fun same_plaintext_seals_differently() {
        // GCM 每次随机 nonce：相同明文两次加密必须不同，否则可由密文相等推断明文相等
        val b = box()
        assertTrue(b.seal("same") != b.seal("same"))
    }

    @Test fun passes_through_legacy_plaintext() {
        // 加密上线前的存量明文应原样返回，支持渐进迁移
        val b = box()
        assertEquals("sk-legacy-plain", b.open("sk-legacy-plain"))
        assertFalse(SecretBox.isEncrypted("sk-legacy-plain"))
    }

    @Test fun handles_unicode_and_empty() {
        val b = box()
        assertEquals("密钥🔑", b.open(b.seal("密钥🔑")))
        assertEquals("", b.open(b.seal("")))
    }

    @Test fun rejects_wrong_key_size() {
        assertFails { SecretBox(SecureRandom.bytes(16)) }
        assertFails { SecretBox(SecureRandom.bytes(64)) }
    }

    @Test fun wrong_key_cannot_open() {
        val sealed = box().seal("secret")
        assertFails { box().open(sealed) }
    }

    @Test fun from_base64_matches_direct() {
        val raw = SecureRandom.bytes(32)
        val sealed = SecretBox(raw).seal("v")
        assertEquals("v", SecretBox.fromBase64(Base64.encode(raw)).open(sealed))
    }
}
