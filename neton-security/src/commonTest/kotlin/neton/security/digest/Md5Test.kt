package neton.security.digest

import kotlin.test.Test
import kotlin.test.assertEquals

/** 用公开测试向量锁死实现：外部签名协议对不上时，先排除是我们算错了。 */
class Md5Test {

    @Test
    fun matchesKnownVectors() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", Md5.hex(""))
        assertEquals("900150983cd24fb0d6963f7d28e17f72", Md5.hex("abc"))
        assertEquals("9e107d9d372bb6826bd81d3542a419d6", Md5.hex("The quick brown fox jumps over the lazy dog"))
    }

    @Test
    fun outputIsLowercaseHex() {
        val hex = Md5.hex("Yese")
        assertEquals(32, hex.length)
        assertEquals(hex.lowercase(), hex, "外部协议普遍要求小写十六进制")
    }
}
