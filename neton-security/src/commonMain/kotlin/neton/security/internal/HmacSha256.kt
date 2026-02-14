package neton.security.internal

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.HMAC
import dev.whyoleg.cryptography.algorithms.SHA256

/**
 * HS256（HMAC-SHA256）薄封装，供 JwtAuthenticator 验签使用。
 * 使用 cryptography-kotlin（CommonCrypto/OpenSSL），不手写 crypto。
 * 外部 API 纯 ByteArray；内部使用 keyDecoder.decodeFromByteArrayBlocking + generateSignatureBlocking。
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

    /**
     * 计算 HMAC-SHA256（cryptography-kotlin Blocking API，纯 ByteArray）
     */
    internal fun sign(secret: ByteArray, data: ByteArray): ByteArray {
        val provider = CryptographyProvider.Default
        val hmac = provider.get(HMAC)
        val decoder = hmac.keyDecoder(SHA256)
        val key = decoder.decodeFromByteArrayBlocking(HMAC.Key.Format.RAW, secret)
        return key.signatureGenerator().generateSignatureBlocking(data)
    }
}
