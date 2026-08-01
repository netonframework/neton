package neton.security.password

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.random.Random
import neton.security.crypto.HmacSha256
import neton.security.internal.constantTimeEquals

object PasswordHasher {
    private const val legacyHexLength = 64
    private const val iterations = 210_000
    private const val saltLength = 16
    private const val keyLength = 32
    private const val algorithm = "pbkdf2-sha256"
    private val legacyHexRegex = Regex("^[0-9a-f]{64}$")

    fun hash(password: String): String {
        val salt = Random.Default.nextBytes(saltLength)
        val derivedKey = pbkdf2HmacSha256(
            password = password.encodeToByteArray(),
            salt = salt,
            iterations = iterations,
            dkLen = keyLength
        )
        return buildString {
            append(algorithm)
            append('$')
            append(iterations)
            append('$')
            append(salt.toBase64Url())
            append('$')
            append(derivedKey.toBase64Url())
        }
    }

    fun verify(password: String, encoded: String): PasswordVerificationResult {
        return when {
            isCurrentFormat(encoded) -> verifyCurrent(password, encoded)
            isLegacySha256(encoded) -> {
                val legacyHash = legacySha256(password)
                PasswordVerificationResult(
                    verified = legacyHash == encoded,
                    needsRehash = legacyHash == encoded
                )
            }
            else -> PasswordVerificationResult(verified = false, needsRehash = false)
        }
    }

    fun needsRehash(encoded: String): Boolean = !isCurrentFormat(encoded)

    private fun verifyCurrent(password: String, encoded: String): PasswordVerificationResult {
        val parts = encoded.split('$')
        if (parts.size != 4 || parts[0] != algorithm) {
            return PasswordVerificationResult(verified = false, needsRehash = false)
        }
        val iterationCount = parts[1].toIntOrNull() ?: return PasswordVerificationResult(false, false)
        val salt = parts[2].fromBase64UrlOrNull() ?: return PasswordVerificationResult(false, false)
        val expected = parts[3].fromBase64UrlOrNull() ?: return PasswordVerificationResult(false, false)
        val actual = pbkdf2HmacSha256(
            password = password.encodeToByteArray(),
            salt = salt,
            iterations = iterationCount,
            dkLen = expected.size
        )
        return PasswordVerificationResult(
            verified = constantTimeEquals(actual, expected),
            needsRehash = iterationCount < iterations
        )
    }

    private fun isCurrentFormat(encoded: String): Boolean = encoded.startsWith("$algorithm$")

    private fun isLegacySha256(encoded: String): Boolean =
        encoded.length == legacyHexLength && legacyHexRegex.matches(encoded)

    private fun legacySha256(rawPassword: String): String {
        val legacySalted = "{sha256}$rawPassword".encodeToByteArray()
        val blockSize = 64
        val digestLength = 32
        val hash = sha256(legacySalted, blockSize, digestLength)
        return hash.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    }

    private fun pbkdf2HmacSha256(password: ByteArray, salt: ByteArray, iterations: Int, dkLen: Int): ByteArray {
        require(iterations > 0) { "iterations must be positive" }
        require(dkLen > 0) { "dkLen must be positive" }

        val hLen = 32
        val blockCount = (dkLen + hLen - 1) / hLen
        val derived = ByteArray(blockCount * hLen)

        for (blockIndex in 1..blockCount) {
            val saltBlock = ByteArray(salt.size + 4)
            salt.copyInto(saltBlock)
            saltBlock[salt.size] = (blockIndex ushr 24).toByte()
            saltBlock[salt.size + 1] = (blockIndex ushr 16).toByte()
            saltBlock[salt.size + 2] = (blockIndex ushr 8).toByte()
            saltBlock[salt.size + 3] = blockIndex.toByte()

            var u = HmacSha256.sign(password, saltBlock)
            val t = u.copyOf()
            repeat(iterations - 1) {
                u = HmacSha256.sign(password, u)
                for (i in t.indices) {
                    t[i] = (t[i].toInt() xor u[i].toInt()).toByte()
                }
            }
            t.copyInto(derived, destinationOffset = (blockIndex - 1) * hLen)
        }

        return derived.copyOf(dkLen)
    }

    // Temporary legacy helper for gradual migration away from single-round SHA-256.
    private fun sha256(input: ByteArray, blockSize: Int, digestLength: Int): ByteArray {
        val k = intArrayOf(
            0x428a2f98.toInt(), 0x71374491, 0xb5c0fbcf.toInt(), 0xe9b5dba5.toInt(),
            0x3956c25b, 0x59f111f1, 0x923f82a4.toInt(), 0xab1c5ed5.toInt(),
            0xd807aa98.toInt(), 0x12835b01, 0x243185be, 0x550c7dc3,
            0x72be5d74, 0x80deb1fe.toInt(), 0x9bdc06a7.toInt(), 0xc19bf174.toInt(),
            0xe49b69c1.toInt(), 0xefbe4786.toInt(), 0x0fc19dc6, 0x240ca1cc,
            0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
            0x983e5152.toInt(), 0xa831c66d.toInt(), 0xb00327c8.toInt(), 0xbf597fc7.toInt(),
            0xc6e00bf3.toInt(), 0xd5a79147.toInt(), 0x06ca6351, 0x14292967,
            0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13,
            0x650a7354, 0x766a0abb, 0x81c2c92e.toInt(), 0x92722c85.toInt(),
            0xa2bfe8a1.toInt(), 0xa81a664b.toInt(), 0xc24b8b70.toInt(), 0xc76c51a3.toInt(),
            0xd192e819.toInt(), 0xd6990624.toInt(), 0xf40e3585.toInt(), 0x106aa070,
            0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5,
            0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
            0x748f82ee, 0x78a5636f, 0x84c87814.toInt(), 0x8cc70208.toInt(),
            0x90befffa.toInt(), 0xa4506ceb.toInt(), 0xbef9a3f7.toInt(), 0xc67178f2.toInt()
        )

        var h0 = 0x6a09e667
        var h1 = 0xbb67ae85.toInt()
        var h2 = 0x3c6ef372
        var h3 = 0xa54ff53a.toInt()
        var h4 = 0x510e527f
        var h5 = 0x9b05688c.toInt()
        var h6 = 0x1f83d9ab
        var h7 = 0x5be0cd19

        val msgLen = input.size
        val bitLen = msgLen.toLong() * 8
        val padLen = (56 - (msgLen + 1) % blockSize + blockSize) % blockSize
        val padded = ByteArray(msgLen + 1 + padLen + 8)
        input.copyInto(padded)
        padded[msgLen] = 0x80.toByte()
        for (i in 0 until 8) {
            padded[padded.size - 1 - i] = (bitLen shr (i * 8)).toByte()
        }

        for (chunkStart in padded.indices step blockSize) {
            val w = IntArray(64)
            for (i in 0 until 16) {
                w[i] = ((padded[chunkStart + i * 4].toInt() and 0xFF) shl 24) or
                    ((padded[chunkStart + i * 4 + 1].toInt() and 0xFF) shl 16) or
                    ((padded[chunkStart + i * 4 + 2].toInt() and 0xFF) shl 8) or
                    (padded[chunkStart + i * 4 + 3].toInt() and 0xFF)
            }
            for (i in 16 until 64) {
                val s0 = w[i - 15].rotateRight(7) xor w[i - 15].rotateRight(18) xor (w[i - 15] ushr 3)
                val s1 = w[i - 2].rotateRight(17) xor w[i - 2].rotateRight(19) xor (w[i - 2] ushr 10)
                w[i] = w[i - 16] + s0 + w[i - 7] + s1
            }

            var a = h0; var b = h1; var c = h2; var d = h3
            var e = h4; var f = h5; var g = h6; var hh = h7

            for (i in 0 until 64) {
                val s1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
                val ch = (e and f) xor (e.inv() and g)
                val temp1 = hh + s1 + ch + k[i] + w[i]
                val s0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
                val maj = (a and b) xor (a and c) xor (b and c)
                val temp2 = s0 + maj

                hh = g; g = f; f = e; e = d + temp1
                d = c; c = b; b = a; a = temp1 + temp2
            }

            h0 += a; h1 += b; h2 += c; h3 += d
            h4 += e; h5 += f; h6 += g; h7 += hh
        }

        return ByteArray(digestLength).also { result ->
            for (i in 0 until 4) {
                result[i] = (h0 shr (24 - i * 8)).toByte()
                result[i + 4] = (h1 shr (24 - i * 8)).toByte()
                result[i + 8] = (h2 shr (24 - i * 8)).toByte()
                result[i + 12] = (h3 shr (24 - i * 8)).toByte()
                result[i + 16] = (h4 shr (24 - i * 8)).toByte()
                result[i + 20] = (h5 shr (24 - i * 8)).toByte()
                result[i + 24] = (h6 shr (24 - i * 8)).toByte()
                result[i + 28] = (h7 shr (24 - i * 8)).toByte()
            }
        }
    }

    private fun Int.rotateRight(n: Int): Int = (this ushr n) or (this shl (32 - n))

    @OptIn(ExperimentalEncodingApi::class)
    private fun ByteArray.toBase64Url(): String = Base64.UrlSafe.encode(this).trimEnd('=')

    @OptIn(ExperimentalEncodingApi::class)
    private fun String.fromBase64UrlOrNull(): ByteArray? {
        val padding = when (length % 4) {
            2 -> "=="
            3 -> "="
            else -> ""
        }
        return try {
            Base64.UrlSafe.decode(this + padding)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

data class PasswordVerificationResult(
    val verified: Boolean,
    val needsRehash: Boolean
)
