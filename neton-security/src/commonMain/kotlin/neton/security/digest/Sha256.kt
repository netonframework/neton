package neton.security.digest

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.SHA256

/**
 * 通用 SHA-256 摘要（hex 小写）。使用 cryptography-kotlin，不手写 crypto。
 * NewGate 网关令牌哈希等场景使用。
 */
object Sha256 {
    fun hex(input: String): String = hex(input.encodeToByteArray())

    @OptIn(ExperimentalStdlibApi::class)
    fun hex(input: ByteArray): String =
        CryptographyProvider.Default.get(SHA256).hasher().hashBlocking(input).toHexString()
}
