package neton.security.crypto

import neton.security.internal.NativeCrypto
import neton.security.random.SecureRandom
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * 对称加密的静态数据保护（AES-256-GCM）。
 *
 * 用于把凭据一类的敏感字段落库：即使数据库泄露，没有主密钥也拿不到明文。
 * box = `iv ‖ ciphertext ‖ tag`（nonce 由库随机生成并内嵌，调用方不需要单独管理）。
 *
 * 密文以 `enc:v<版本>:<base64(box)>` 形式存储——带版本前缀，便于日后轮换密钥或升级算法时
 * 区分新旧记录；[isEncrypted] 可识别尚未加密的存量明文，支持渐进迁移。
 */
@OptIn(ExperimentalEncodingApi::class)
class SecretBox(masterKey: ByteArray, private val version: Int = 1) {

    private val key: ByteArray = masterKey.copyOf().also {
        require(it.size == 32) { "master key must be 32 bytes (AES-256), got ${it.size}" }
    }

    fun seal(plaintext: String): String {
        val nonce = SecureRandom.bytes(12)
        val ciphertext = NativeCrypto.gcm(key, nonce, plaintext.encodeToByteArray(), byteArrayOf(), true)
        return PREFIX + version + ":" + Base64.encode(nonce + ciphertext)
    }

    /**
     * 解开密文；[stored] 若不是本工具产生的密文则原样返回，便于「加密上线前已存明文」的平滑迁移。
     */
    fun open(stored: String): String {
        if (!isEncrypted(stored)) return stored
        val payload = stored.substringAfter(':', "").substringAfter(':', "")
        val box = Base64.decode(payload)
        require(box.size >= 28) { "Invalid encrypted payload" }
        return NativeCrypto.gcm(key, box.copyOfRange(0, 12), box.copyOfRange(12, box.size),
            byteArrayOf(), false).decodeToString()
    }

    companion object {
        private const val PREFIX = "enc:v"

        /** 是否为本工具产生的密文（用于识别存量明文）。 */
        fun isEncrypted(value: String): Boolean =
            value.startsWith(PREFIX) && value.count { it == ':' } >= 2

        /** 从 base64 主密钥构造；解码后必须是 32 字节。 */
        fun fromBase64(masterKeyBase64: String, version: Int = 1): SecretBox =
            SecretBox(Base64.decode(masterKeyBase64), version)

    }
}
