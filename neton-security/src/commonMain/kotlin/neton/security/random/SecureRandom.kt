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
     * 用拒绝采样消除取模偏置（`nextInt % n` 在 n 不整除 256 时低位字符更常见——
     * 对令牌而言即熵降低）。
     */
    fun string(length: Int, alphabet: String): String {
        require(length > 0) { "length must be positive" }
        require(alphabet.isNotEmpty()) { "alphabet must not be empty" }
        val n = alphabet.length
        // 最大可用值：截断到 n 的整数倍，超出部分丢弃重取
        val limit = 256 - (256 % n)
        val sb = StringBuilder(length)
        while (sb.length < length) {
            for (b in bytes(length)) {
                val v = b.toInt() and 0xFF
                if (v >= limit) continue  // 拒绝：会引入偏置
                sb.append(alphabet[v % n])
                if (sb.length == length) break
            }
        }
        return sb.toString()
    }
}
