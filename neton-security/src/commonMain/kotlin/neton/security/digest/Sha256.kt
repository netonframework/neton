package neton.security.digest

import neton.openssl.OpenSsl

/**
 * 通用 SHA-256 摘要（hex 小写）。使用 Neton OpenSSL 4，不手写 crypto。
 * NewGate 网关令牌哈希等场景使用。
 */
object Sha256 {
    fun hex(input: String): String = hex(input.encodeToByteArray())

    @OptIn(ExperimentalStdlibApi::class)
    fun hex(input: ByteArray): String =
        OpenSsl.sha256(input).toHexString()
}
