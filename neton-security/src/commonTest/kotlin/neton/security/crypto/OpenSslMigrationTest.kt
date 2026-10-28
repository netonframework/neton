package neton.security.crypto

import kotlin.io.encoding.Base64
import kotlin.test.*
import neton.openssl.OpenSsl
import neton.security.internal.NativeCrypto
import neton.security.internal.rsaJwkSpki
import neton.security.password.PasswordHasher
import kotlinx.coroutines.test.runTest
import neton.http.client.HttpClient
import neton.http.client.create

@OptIn(ExperimentalStdlibApi::class)
class OpenSslMigrationTest {
    @Test fun realHttpClientAndSecurityShareRuntime() = runTest {
        val client = HttpClient.create()
        try {
            assertTrue(client.capabilities.isNotEmpty())
            val box = SecretBox(ByteArray(32))
            assertEquals("shared-runtime", box.open(box.seal("shared-runtime")))
            OpenSsl.checkVersion()
        } finally { client.close() }
    }

    @Test fun linkedRuntimeIsOpenSsl4() {
        OpenSsl.checkVersion()
        assertTrue(OpenSsl.version.startsWith("OpenSSL 4."))
    }

    @Test fun nistEmptyGcmVectorsCoverAllKeySizes() {
        val vectors = mapOf(
            16 to "58e2fccefa7e3061367f1d57a4e7455a",
            24 to "cd33b28ac773f74ba00ed1f312572435",
            32 to "530f8afbc74536b9a963b4f1c4cb738b",
        )
        for ((size, tag) in vectors) {
            val key = ByteArray(size)
            val nonce = ByteArray(12)
            val expected = tag.hexToByteArray()
            assertContentEquals(expected, NativeCrypto.gcm(key, nonce, byteArrayOf(), byteArrayOf(), true))
            assertContentEquals(byteArrayOf(), AesGcm.decrypt(key, nonce, expected))
            val bad = expected.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
            assertNull(AesGcm.decrypt(key, nonce, bad))
            // Failed authentication must not poison the next operation's error state.
            assertContentEquals(byteArrayOf(), AesGcm.decrypt(key, nonce, expected))
        }
    }

    @Test fun secretBoxRetainsPersistedNonceCiphertextTagLayout() {
        val persisted = "enc:v1:" + Base64.encode(ByteArray(12) + "530f8afbc74536b9a963b4f1c4cb738b".hexToByteArray())
        assertEquals("", SecretBox(ByteArray(32)).open(persisted))
        val key = ByteArray(32)
        val box = SecretBox(key)
        key.fill(1)
        assertEquals("", box.open(persisted), "Constructor must own its key copy")
        assertFails { box.open("enc:v1:AA==") }
    }

    @Test fun rfc2202Sha1HmacAndEmbeddedNulPbkdf2() {
        assertEquals("b617318655057264e28bc0b6fb378c8ef146be00",
            NativeCrypto.hmac("SHA1", ByteArray(20) { 0x0b }, "Hi There".encodeToByteArray(), 20).toHexString())
        // PBKDF2-HMAC-SHA256, password/salt with embedded NUL; explicit lengths must be used.
        assertEquals("89b69d0516f829893c696226650a8687",
            NativeCrypto.pbkdf2("pass\u0000word".encodeToByteArray(), "sa\u0000lt".encodeToByteArray(), 4096, 16).toHexString())
    }

    @Test fun malformedInputsFailClosed() {
        assertFalse(PasswordHasher.verify("p", "pbkdf2-sha256\$0\$c2FsdA\$AA").verified)
        assertFalse(PasswordHasher.verify("p", "pbkdf2-sha256\$1\$c2FsdA\$").verified)
        assertFalse(RsaSha256.verifyBase64("AA==", byteArrayOf(), "AA=="))
        assertFails { rsaJwkSpki("AA", "AQAB") }
        assertFails { rsaJwkSpki("?", "AQAB") }
    }
}
