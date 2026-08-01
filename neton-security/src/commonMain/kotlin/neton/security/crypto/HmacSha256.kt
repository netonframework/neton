package neton.security.crypto

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.HMAC
import dev.whyoleg.cryptography.algorithms.SHA256
import neton.security.internal.constantTimeEquals

/**
 * HMAC-SHA256（HS256）薄封装：签名、验签、hex 编解码。
 *
 * 与 [SecretBox]、[neton.security.digest.Sha256]、[neton.security.password.PasswordHasher]
 * 同属公开加密 API。业务侧的签名场景（开放平台验签、敏感字段索引哈希等）直接用本类，
 * 不要去 import `neton.security.internal.*`。
 *
 * 底层走 cryptography-kotlin（CommonCrypto/OpenSSL），不手写 crypto。
 * 对外一律 ByteArray/hex String，不暴露 provider 类型。
 *
 * @see Neton-JWT-Authenticator-Spec-v1.md
 */
object HmacSha256 {

    /**
     * 验证 HMAC-SHA256 签名（constant-time 比较，禁止用 == 或 contentEquals）
     * @param secret 密钥（UTF-8）
     * @param signingInput 待签数据（JWT header.payload 原始串）
     * @param signature 收到的签名
     * @return true 校验通过
     */
    fun verify(secret: ByteArray, signingInput: ByteArray, signature: ByteArray): Boolean {
        val expected = sign(secret, signingInput)
        return constantTimeEquals(expected, signature)
    }

    @OptIn(ExperimentalStdlibApi::class)
    fun verifyHex(secret: ByteArray, signingInput: ByteArray, signatureHex: String): Boolean {
        val expected = sign(secret, signingInput)
        val actual = try {
            signatureHex.hexToByteArray()
        } catch (_: IllegalArgumentException) {
            return false
        }
        return constantTimeEquals(expected, actual)
    }

    @OptIn(ExperimentalStdlibApi::class)
    fun signHex(secret: ByteArray, data: ByteArray): String = sign(secret, data).toHexString()

    /**
     * 计算 HMAC-SHA256，返回原始字节。
     *
     * @param secret 密钥
     * @param data 待签数据
     */
    fun sign(secret: ByteArray, data: ByteArray): ByteArray {
        val provider = CryptographyProvider.Default
        val hmac = provider.get(HMAC)
        val decoder = hmac.keyDecoder(SHA256)
        val key = decoder.decodeFromByteArrayBlocking(HMAC.Key.Format.RAW, secret)
        return key.signatureGenerator().generateSignatureBlocking(data)
    }
}
