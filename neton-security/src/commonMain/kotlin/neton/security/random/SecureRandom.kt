package neton.security.random

import dev.whyoleg.cryptography.random.CryptographyRandom

/**
 * 密码学安全随机源（CSPRNG）。
 *
 * `kotlin.random.Random` 是可预测的 PRNG，**不得**用于 API 令牌、会话/结算标识、盐等安全敏感场景
 * （给定若干输出即可推断内部状态并预测后续值）。本对象基于 cryptography-kotlin 的
 * [CryptographyRandom]（Native 走平台 CSPRNG：Darwin CommonCrypto / Linux OpenSSL）。
 */
object SecureRandom {

    /** [size] 字节的密码学安全随机数据。 */
    fun bytes(size: Int): ByteArray {
        require(size > 0) { "size must be positive" }
        return CryptographyRandom.nextBytes(size)
    }

    /** [byteLength] 字节随机数据的小写 hex（长度为 `byteLength * 2`）。 */
    @OptIn(ExperimentalStdlibApi::class)
    fun hex(byteLength: Int): String = bytes(byteLength).toHexString()

    /**
     * 从 [alphabet] 均匀取样的 [length] 位随机串。
     *
     * 按 `ceil(log2(n))` 位宽取样并拒绝越界值，消除取模偏置（`v % n` 在 n 不是 2 的幂时
     * 低位字符更常见——对令牌而言即熵降低）。位宽随 n 自适应，**任意字母表大小都不会死循环**
     * （固定按单字节取样时，n > 256 会让可接受区间为空）。
     *
     * @throws IllegalArgumentException 字母表为空或含重复字符（重复会静默降低熵）。
     */
    fun string(length: Int, alphabet: String): String {
        require(length > 0) { "length must be positive" }
        require(alphabet.isNotEmpty()) { "alphabet must not be empty" }
        require(alphabet.toSet().size == alphabet.length) { "alphabet must not contain duplicate characters" }
        val n = alphabet.length
        val bits = 32 - (n - 1).countLeadingZeroBits()   // ceil(log2(n))；n=1 时为 0
        val byteCount = maxOf(1, (bits + 7) / 8)
        val mask = (1L shl bits) - 1                     // n=1 → mask=0，v 恒 0 恒被接受
        val sb = StringBuilder(length)
        while (sb.length < length) {
            // 批量取样：按拒绝率留余量，减少 CSPRNG 调用次数
            val buf = bytes(byteCount * (length - sb.length) * 2)
            var i = 0
            while (i + byteCount <= buf.size && sb.length < length) {
                var v = 0L
                for (k in 0 until byteCount) v = (v shl 8) or (buf[i + k].toLong() and 0xFF)
                i += byteCount
                v = v and mask
                if (v < n) sb.append(alphabet[v.toInt()])  // 拒绝越界值，保持均匀
            }
        }
        return sb.toString()
    }
}
