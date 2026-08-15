package neton.security.crypto

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.DelicateCryptographyApi
import dev.whyoleg.cryptography.algorithms.AES

/**
 * AES-GCM，**由调用方提供 nonce 与附加认证数据（AAD）**。
 *
 * 与 [SecretBox] 的区别：那个是我们自己存数据用的，nonce 由库随机生成并内嵌，
 * 调用方不需要关心；这里是对接**外部既定协议**用的 —— 对方把 nonce 与 AAD
 * 分开放在报文字段里（微信支付 V3 的回调就是 `nonce` + `associated_data` + `ciphertext`），
 * 我们只能按它给的来。
 *
 * GCM 的 nonce 绝不可在同一密钥下重复：重复会同时毁掉机密性与完整性。
 * 本类只做解密，不提供加密入口，正是为了不给"自己造 nonce"留口子。
 */
@OptIn(DelicateCryptographyApi::class)
object AesGcm {

    /**
     * 解密并校验认证标签。
     *
     * 任何失败（标签不匹配、长度不对、密钥错）都返回 null 而不是抛异常：
     * 调用点是公网回调入口，畸形输入应当被安静拒绝，不该把异常细节透出去。
     *
     * @param key 对称密钥，AES-128/192/256 对应 16/24/32 字节
     * @param nonce 随机数，GCM 标准为 12 字节
     * @param ciphertext 密文，**尾部含 16 字节认证标签**（多数协议如此打包）
     * @param associatedData 参与认证但不加密的数据；对不上同样解不开
     */
    fun decrypt(
        key: ByteArray,
        nonce: ByteArray,
        ciphertext: ByteArray,
        associatedData: ByteArray? = null,
    ): ByteArray? = try {
        CryptographyProvider.Default.get(AES.GCM)
            .keyDecoder()
            .decodeFromByteArrayBlocking(AES.Key.Format.RAW, key)
            .cipher()
            .decryptWithIvBlocking(nonce, ciphertext, associatedData)
    } catch (_: Throwable) {
        null
    }
}
