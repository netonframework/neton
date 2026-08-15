package neton.security.crypto

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 与标准实现的互操作（测试向量由 Python cryptography 的 AESGCM 生成）。
 *
 * 微信支付 V3 的回调正文就是这个格式：`nonce` + `associated_data` + base64 密文（尾含 tag）。
 * 解不开就等于收不到支付结果，而报错只会是"解密失败"，看不出是密钥、nonce 还是 AAD 的问题，
 * 所以用固定向量把三者都钉死。
 */
@OptIn(ExperimentalEncodingApi::class)
class AesGcmTest {

    private val key = ByteArray(32) { it.toByte() }
    private val nonce = ByteArray(12) { it.toByte() }
    private val aad = "transaction".encodeToByteArray()
    private val ciphertext = Base64.decode(
        "PCC5brG6tmnsJfLU34ZaV6Gh/xmfCTsZSkrUpzFLdMBgdMuj3LVz7BGGRc/b0mt7qwozryeE7mUcFtNhWAsxhcF8lM+V"
    )
    private val expected = """{"out_trade_no":"wx-order-1","trade_state":"SUCCESS"}"""

    @Test
    fun decryptsStandardVector() {
        val plain = AesGcm.decrypt(key, nonce, ciphertext, aad)
        assertEquals(expected, plain?.decodeToString())
    }

    /** AAD 对不上必须解不开 —— 它参与认证，正是防篡改的一环。 */
    @Test
    fun wrongAssociatedDataFails() {
        assertNull(AesGcm.decrypt(key, nonce, ciphertext, "refund".encodeToByteArray()))
    }

    @Test
    fun wrongKeyOrNonceFails() {
        assertNull(AesGcm.decrypt(ByteArray(32) { 9 }, nonce, ciphertext, aad))
        assertNull(AesGcm.decrypt(key, ByteArray(12) { 9 }, ciphertext, aad))
    }

    /** 密文被改动必须解不开（认证标签的作用）。 */
    @Test
    fun tamperedCiphertextFails() {
        val tampered = ciphertext.copyOf().also { it[0] = (it[0] + 1).toByte() }
        assertNull(AesGcm.decrypt(key, nonce, tampered, aad))
    }

    /** 畸形输入返回 null 而不是抛异常：调用点是公网回调入口。 */
    @Test
    fun malformedInputIsRejectedNotThrown() {
        assertNull(AesGcm.decrypt(key, nonce, ByteArray(0), aad))
        assertNull(AesGcm.decrypt(ByteArray(3), nonce, ciphertext, aad))
    }
}
