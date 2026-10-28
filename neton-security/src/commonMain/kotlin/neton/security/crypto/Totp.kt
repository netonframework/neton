package neton.security.crypto

import neton.security.internal.NativeCrypto

/** RFC 6238 SHA-1 profile used by authenticator apps. */
object Totp {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    fun base32(bytes: ByteArray): String {
        var acc = 0; var bits = 0
        return buildString {
            for (b in bytes) {
                acc = (acc shl 8) or (b.toInt() and 255); bits += 8
                while (bits >= 5) { bits -= 5; append(ALPHABET[(acc ushr bits) and 31]) }
            }
            if (bits > 0) append(ALPHABET[(acc shl (5 - bits)) and 31])
        }
    }
    fun decode(secret: String): ByteArray {
        var acc = 0; var bits = 0
        val out = mutableListOf<Byte>()
        for (c in secret) {
            val v = ALPHABET.indexOf(c); require(v >= 0) { "Invalid Base32" }
            acc = (acc shl 5) or v; bits += 5
            if (bits >= 8) { bits -= 8; out.add((acc ushr bits).toByte()) }
        }
        return out.toByteArray()
    }
    fun code(secret: ByteArray, step: Long, digits: Int = 6): String {
        require(step >= 0 && digits in 6..8)
        val data = ByteArray(8) { (step ushr ((7 - it) * 8)).toByte() }
        val digest = NativeCrypto.hmac("SHA1", secret, data, 20)
        val offset = digest.last().toInt() and 15
        var value = digest[offset].toInt() and 127
        for (i in 1..3) value = (value shl 8) or (digest[offset + i].toInt() and 255)
        val modulus = when (digits) { 6 -> 1_000_000; 7 -> 10_000_000; else -> 100_000_000 }
        return (value % modulus).toString().padStart(digits, '0')
    }
    fun match(secret: String, candidate: String, epochSeconds: Long, lastStep: Long): Long? {
        if (candidate.length != 6 || candidate.any { it !in '0'..'9' }) return null
        val key = decode(secret)
        for (step in (epochSeconds / 30 - 1)..(epochSeconds / 30 + 1)) {
            if (step < 0 || step <= lastStep) continue
            val expected = code(key, step)
            var diff = 0
            for (i in expected.indices) diff = diff or (expected[i].code xor candidate[i].code)
            if (diff == 0) return step
        }
        return null
    }
}
