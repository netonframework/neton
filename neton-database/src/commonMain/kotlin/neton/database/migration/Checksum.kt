package neton.database.migration

/**
 * SHA-256 of raw bytes — 一字节变化视为脚本变更。
 *
 * 纯 Kotlin 实现(无 expect/actual),Native 友好,无外部依赖。
 * 参考 RFC 6234。
 */
@Suppress("REDUNDANT_CALL_OF_CONVERSION_METHOD")
internal object Checksum {

    fun sha256Hex(bytes: ByteArray): String {
        val hash = sha256(bytes)
        val sb = StringBuilder(hash.size * 2)
        for (b in hash) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[(v ushr 4) and 0x0F])
            sb.append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()

    private val K = intArrayOf(
        0x428a2f98.toInt(), 0x71374491.toInt(), 0xb5c0fbcf.toInt(), 0xe9b5dba5.toInt(),
        0x3956c25b.toInt(), 0x59f111f1.toInt(), 0x923f82a4.toInt(), 0xab1c5ed5.toInt(),
        0xd807aa98.toInt(), 0x12835b01.toInt(), 0x243185be.toInt(), 0x550c7dc3.toInt(),
        0x72be5d74.toInt(), 0x80deb1fe.toInt(), 0x9bdc06a7.toInt(), 0xc19bf174.toInt(),
        0xe49b69c1.toInt(), 0xefbe4786.toInt(), 0x0fc19dc6.toInt(), 0x240ca1cc.toInt(),
        0x2de92c6f.toInt(), 0x4a7484aa.toInt(), 0x5cb0a9dc.toInt(), 0x76f988da.toInt(),
        0x983e5152.toInt(), 0xa831c66d.toInt(), 0xb00327c8.toInt(), 0xbf597fc7.toInt(),
        0xc6e00bf3.toInt(), 0xd5a79147.toInt(), 0x06ca6351.toInt(), 0x14292967.toInt(),
        0x27b70a85.toInt(), 0x2e1b2138.toInt(), 0x4d2c6dfc.toInt(), 0x53380d13.toInt(),
        0x650a7354.toInt(), 0x766a0abb.toInt(), 0x81c2c92e.toInt(), 0x92722c85.toInt(),
        0xa2bfe8a1.toInt(), 0xa81a664b.toInt(), 0xc24b8b70.toInt(), 0xc76c51a3.toInt(),
        0xd192e819.toInt(), 0xd6990624.toInt(), 0xf40e3585.toInt(), 0x106aa070.toInt(),
        0x19a4c116.toInt(), 0x1e376c08.toInt(), 0x2748774c.toInt(), 0x34b0bcb5.toInt(),
        0x391c0cb3.toInt(), 0x4ed8aa4a.toInt(), 0x5b9cca4f.toInt(), 0x682e6ff3.toInt(),
        0x748f82ee.toInt(), 0x78a5636f.toInt(), 0x84c87814.toInt(), 0x8cc70208.toInt(),
        0x90befffa.toInt(), 0xa4506ceb.toInt(), 0xbef9a3f7.toInt(), 0xc67178f2.toInt()
    )

    private fun sha256(message: ByteArray): ByteArray {
        val msgLen = message.size
        val bitLen = msgLen.toLong() * 8L
        val padLen = ((56 - (msgLen + 1) % 64) + 64) % 64
        val padded = ByteArray(msgLen + 1 + padLen + 8)
        message.copyInto(padded, 0)
        padded[msgLen] = 0x80.toByte()
        for (i in 0 until 8) {
            padded[padded.size - 1 - i] = ((bitLen ushr (8 * i)) and 0xFFL).toByte()
        }

        var h0 = 0x6a09e667.toInt()
        var h1 = 0xbb67ae85.toInt()
        var h2 = 0x3c6ef372.toInt()
        var h3 = 0xa54ff53a.toInt()
        var h4 = 0x510e527f.toInt()
        var h5 = 0x9b05688c.toInt()
        var h6 = 0x1f83d9ab.toInt()
        var h7 = 0x5be0cd19.toInt()

        val w = IntArray(64)
        var blockOffset = 0
        while (blockOffset < padded.size) {
            for (i in 0 until 16) {
                val off = blockOffset + i * 4
                w[i] = ((padded[off].toInt() and 0xFF) shl 24) or
                    ((padded[off + 1].toInt() and 0xFF) shl 16) or
                    ((padded[off + 2].toInt() and 0xFF) shl 8) or
                    (padded[off + 3].toInt() and 0xFF)
            }
            for (i in 16 until 64) {
                val s0 = rotr(w[i - 15], 7) xor rotr(w[i - 15], 18) xor (w[i - 15] ushr 3)
                val s1 = rotr(w[i - 2], 17) xor rotr(w[i - 2], 19) xor (w[i - 2] ushr 10)
                w[i] = w[i - 16] + s0 + w[i - 7] + s1
            }

            var a = h0; var b = h1; var c = h2; var d = h3
            var e = h4; var f = h5; var g = h6; var hh = h7

            for (i in 0 until 64) {
                val s1 = rotr(e, 6) xor rotr(e, 11) xor rotr(e, 25)
                val ch = (e and f) xor (e.inv() and g)
                val t1 = hh + s1 + ch + K[i] + w[i]
                val s0 = rotr(a, 2) xor rotr(a, 13) xor rotr(a, 22)
                val mj = (a and b) xor (a and c) xor (b and c)
                val t2 = s0 + mj
                hh = g; g = f; f = e; e = d + t1
                d = c; c = b; b = a; a = t1 + t2
            }

            h0 += a; h1 += b; h2 += c; h3 += d
            h4 += e; h5 += f; h6 += g; h7 += hh

            blockOffset += 64
        }

        return byteArrayOf(
            ((h0 ushr 24) and 0xFF).toByte(), ((h0 ushr 16) and 0xFF).toByte(),
            ((h0 ushr 8) and 0xFF).toByte(), (h0 and 0xFF).toByte(),
            ((h1 ushr 24) and 0xFF).toByte(), ((h1 ushr 16) and 0xFF).toByte(),
            ((h1 ushr 8) and 0xFF).toByte(), (h1 and 0xFF).toByte(),
            ((h2 ushr 24) and 0xFF).toByte(), ((h2 ushr 16) and 0xFF).toByte(),
            ((h2 ushr 8) and 0xFF).toByte(), (h2 and 0xFF).toByte(),
            ((h3 ushr 24) and 0xFF).toByte(), ((h3 ushr 16) and 0xFF).toByte(),
            ((h3 ushr 8) and 0xFF).toByte(), (h3 and 0xFF).toByte(),
            ((h4 ushr 24) and 0xFF).toByte(), ((h4 ushr 16) and 0xFF).toByte(),
            ((h4 ushr 8) and 0xFF).toByte(), (h4 and 0xFF).toByte(),
            ((h5 ushr 24) and 0xFF).toByte(), ((h5 ushr 16) and 0xFF).toByte(),
            ((h5 ushr 8) and 0xFF).toByte(), (h5 and 0xFF).toByte(),
            ((h6 ushr 24) and 0xFF).toByte(), ((h6 ushr 16) and 0xFF).toByte(),
            ((h6 ushr 8) and 0xFF).toByte(), (h6 and 0xFF).toByte(),
            ((h7 ushr 24) and 0xFF).toByte(), ((h7 ushr 16) and 0xFF).toByte(),
            ((h7 ushr 8) and 0xFF).toByte(), (h7 and 0xFF).toByte()
        )
    }

    private fun rotr(x: Int, n: Int): Int = (x ushr n) or (x shl (32 - n))
}
