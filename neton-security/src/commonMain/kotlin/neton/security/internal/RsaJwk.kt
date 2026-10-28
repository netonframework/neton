package neton.security.internal

import kotlin.io.encoding.Base64

/** Encode unsigned RSA JWK integers as X.509 SPKI; cryptography stays in OpenSSL. */
internal fun rsaJwkSpki(n: String, e: String): ByteArray {
    fun integer(encoded: String): ByteArray {
        require(encoded.isNotEmpty() && encoded.length <= 16384)
        val bytes = Base64.UrlSafe.decode(encoded + "=".repeat((4 - encoded.length % 4) % 4))
        require(bytes.isNotEmpty() && bytes.any { it != 0.toByte() })
        val unsigned = bytes.dropWhile { it == 0.toByte() }.toByteArray()
        return der(2, if (unsigned[0] < 0) byteArrayOf(0) + unsigned else unsigned)
    }
    val rsa = der(0x30, integer(n) + integer(e))
    // rsaEncryption OID (1.2.840.113549.1.1.1), NULL parameters.
    val algorithm = byteArrayOf(0x30, 0x0d, 0x06, 0x09, 0x2a, 0x86.toByte(), 0x48,
        0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00)
    return der(0x30, algorithm + der(3, byteArrayOf(0) + rsa))
}

private fun der(tag: Int, value: ByteArray): ByteArray {
    val length = if (value.size < 128) byteArrayOf(value.size.toByte()) else {
        val bytes = mutableListOf<Byte>()
        var remaining = value.size
        while (remaining > 0) { bytes.add(0, remaining.toByte()); remaining = remaining ushr 8 }
        byteArrayOf((0x80 or bytes.size).toByte()) + bytes.toByteArray()
    }
    return byteArrayOf(tag.toByte()) + length + value
}
