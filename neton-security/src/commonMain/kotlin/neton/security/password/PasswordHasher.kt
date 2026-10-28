package neton.security.password

import kotlin.io.encoding.Base64
import neton.security.digest.Sha256
import neton.security.internal.NativeCrypto
import neton.security.internal.constantTimeEquals
import neton.security.random.SecureRandom

object PasswordHasher {
    private const val iterations = 210_000
    private const val saltLength = 16
    private const val keyLength = 32
    private const val algorithm = "pbkdf2-sha256"
    private val legacyHexRegex = Regex("^[0-9a-f]{64}$")

    /** OpenSSL 4 PBKDF2-HMAC-SHA256; existing database hashes need no migration. */
    fun hash(password: String): String {
        val salt = SecureRandom.bytes(saltLength)
        val derived = NativeCrypto.pbkdf2(password.encodeToByteArray(), salt, iterations, keyLength)
        return listOf(algorithm, iterations.toString(), salt.toBase64Url(), derived.toBase64Url()).joinToString("$")
    }

    fun verify(password: String, encoded: String): PasswordVerificationResult = when {
        isCurrentFormat(encoded) -> verifyCurrent(password, encoded)
        legacyHexRegex.matches(encoded) -> {
            val valid = constantTimeEquals(Sha256.hex("{sha256}$password").encodeToByteArray(), encoded.encodeToByteArray())
            PasswordVerificationResult(valid, valid)
        }
        else -> PasswordVerificationResult(false, false)
    }

    fun needsRehash(encoded: String): Boolean = !isCurrentFormat(encoded)

    private fun verifyCurrent(password: String, encoded: String): PasswordVerificationResult {
        val parts = encoded.split('$')
        if (parts.size != 4 || parts[0] != algorithm) return PasswordVerificationResult(false, false)
        val rounds = parts[1].toIntOrNull() ?: return PasswordVerificationResult(false, false)
        val salt = parts[2].fromBase64UrlOrNull() ?: return PasswordVerificationResult(false, false)
        val expected = parts[3].fromBase64UrlOrNull() ?: return PasswordVerificationResult(false, false)
        if (rounds <= 0 || salt.isEmpty() || expected.isEmpty()) return PasswordVerificationResult(false, false)
        val actual = NativeCrypto.pbkdf2(password.encodeToByteArray(), salt, rounds, expected.size)
        return PasswordVerificationResult(constantTimeEquals(actual, expected), rounds < iterations)
    }

    private fun isCurrentFormat(encoded: String): Boolean = encoded.startsWith("$algorithm$")
    private fun ByteArray.toBase64Url(): String = Base64.UrlSafe.encode(this).trimEnd('=')
    private fun String.fromBase64UrlOrNull(): ByteArray? = try {
        Base64.UrlSafe.decode(this + "=".repeat((4 - length % 4) % 4))
    } catch (_: IllegalArgumentException) { null }
}

data class PasswordVerificationResult(val verified: Boolean, val needsRehash: Boolean)
