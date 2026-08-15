package neton.security.digest

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.DelicateCryptographyApi
import dev.whyoleg.cryptography.algorithms.MD5

/**
 * MD5 摘要。
 *
 * **不要用于口令存储或任何安全目的** —— MD5 早已可构造碰撞。
 * 它留在这里只为一个用途：对接**外部系统既定的签名协议**。
 * 不少支付、短信网关的签名算法就是"参数排序拼接后取 MD5"，
 * 我方无权更改，只能照做；此时安全性由传输通道与密钥保密性承担。
 *
 * 自研协议一律用 [Sha256] 或 HMAC。
 */
@OptIn(DelicateCryptographyApi::class)
object Md5 {

    private val hasher by lazy { CryptographyProvider.Default.get(MD5).hasher() }

    fun hash(data: ByteArray): ByteArray = hasher.hashBlocking(data)

    @OptIn(ExperimentalStdlibApi::class)
    fun hex(data: ByteArray): String = hash(data).toHexString()

    /** 按 UTF-8 取摘要，返回小写十六进制 —— 外部协议普遍要求小写。 */
    fun hex(text: String): String = hex(text.encodeToByteArray())
}
