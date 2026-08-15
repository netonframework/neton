package neton.security.crypto

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.RSA
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * RSA-SHA256 签名与验签（PKCS#1 v1.5，即 Java 侧的 `SHA256withRSA`）。
 *
 * 对外支付网关普遍用它做请求签名与回调验签：支付宝的 RSA2、微信支付 V3 都是这一套。
 * 与 [HmacSha256] 的区别是非对称 —— 我方用**自己的私钥**签请求，用**对方的公钥**验回调，
 * 因此对方无法伪造我方请求，我方也无法否认已发出的请求。
 *
 * 密钥接受 PEM 或裸 base64（PKCS#8 私钥 / X.509 公钥），因为各家文档给出的形式不一：
 * 有的带 `-----BEGIN` 头，有的只给一长串 base64。让调用方去纠结这个纯属浪费。
 */
@OptIn(ExperimentalEncodingApi::class)
object RsaSha256 {

    /**
     * 用私钥签名，返回 base64。
     *
     * @param privateKey PKCS#8 私钥，PEM 或裸 base64
     */
    fun signBase64(privateKey: String, data: ByteArray): String =
        Base64.encode(sign(privateKey, data))

    fun sign(privateKey: String, data: ByteArray): ByteArray =
        CryptographyProvider.Default.get(RSA.PKCS1)
            .privateKeyDecoder(SHA256)
            .decodeFromByteArrayBlocking(RSA.PrivateKey.Format.DER, decodeKey(privateKey))
            .signatureGenerator()
            .generateSignatureBlocking(data)

    /**
     * 用对方公钥验签。
     *
     * 任何异常都按验签失败处理：回调是公网入口，畸形输入应当被拒绝而不是把异常抛到上层
     * —— 后者会让攻击者用格式错误的请求探测出内部实现。
     *
     * @param publicKey X.509 公钥，PEM 或裸 base64
     */
    fun verifyBase64(publicKey: String, data: ByteArray, signatureBase64: String): Boolean = try {
        CryptographyProvider.Default.get(RSA.PKCS1)
            .publicKeyDecoder(SHA256)
            .decodeFromByteArrayBlocking(RSA.PublicKey.Format.DER, decodeKey(publicKey))
            .signatureVerifier()
            .tryVerifySignatureBlocking(data, Base64.decode(signatureBase64))
    } catch (_: Throwable) {
        false
    }

    /** 去掉 PEM 头尾与所有空白，还原成 DER 字节。 */
    private fun decodeKey(key: String): ByteArray {
        val body = key.lineSequence()
            .filterNot { it.startsWith("-----") }
            .joinToString("")
            .filterNot { it.isWhitespace() }
        return Base64.decode(body)
    }
}
