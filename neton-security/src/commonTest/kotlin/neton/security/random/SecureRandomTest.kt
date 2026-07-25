package neton.security.random

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class SecureRandomTest {

    @Test fun bytes_has_requested_size() {
        assertEquals(32, SecureRandom.bytes(32).size)
        assertEquals(1, SecureRandom.bytes(1).size)
    }

    @Test fun bytes_rejects_non_positive() {
        assertFails { SecureRandom.bytes(0) }
        assertFails { SecureRandom.bytes(-1) }
    }

    @Test fun bytes_do_not_repeat() {
        // 连续取样重复即说明不是随机源（非严格证明，但能抓到常量/计数器实现）
        val seen = HashSet<String>()
        repeat(50) { seen += SecureRandom.bytes(16).joinToString(",") }
        assertEquals(50, seen.size)
    }

    @Test fun hex_length_is_double_byte_length() {
        assertEquals(64, SecureRandom.hex(32).length)
        assertEquals(32, SecureRandom.hex(16).length)
        assertTrue(SecureRandom.hex(16).all { it in "0123456789abcdef" })
    }

    @Test fun string_uses_only_alphabet_and_exact_length() {
        val alphabet = "ABCdef123"
        val s = SecureRandom.string(64, alphabet)
        assertEquals(64, s.length)
        assertTrue(s.all { it in alphabet }, "out-of-alphabet char in $s")
    }

    @Test fun string_rejects_bad_args() {
        assertFails { SecureRandom.string(0, "abc") }
        assertFails { SecureRandom.string(8, "") }
        assertFails { SecureRandom.string(8, "aab") }  // 重复字符会静默降低熵
    }

    @Test fun string_handles_alphabet_larger_than_256() {
        // 按单字节取样时 n>256 会让可接受区间为空 → 永久死循环。位宽自适应后必须正常返回。
        val alphabet = (0 until 257).map { (0x4E00 + it).toChar() }.joinToString("")
        assertEquals(257, alphabet.toSet().size)
        val s = SecureRandom.string(200, alphabet)
        assertEquals(200, s.length)
        assertTrue(s.all { it in alphabet })
    }

    @Test fun string_handles_single_char_alphabet() {
        assertEquals("xxxxx", SecureRandom.string(5, "x"))
    }

    @Test fun string_handles_power_of_two_alphabet() {
        val alphabet = "0123456789abcdef"  // 16：mask 全接受，不应退化
        val s = SecureRandom.string(128, alphabet)
        assertEquals(128, s.length)
        assertTrue(s.all { it in alphabet })
        assertTrue(s.toSet().size >= 12, "16 字符表 128 位取样应覆盖大部分字符：${s.toSet().size}")
    }

    @Test fun string_covers_alphabet_without_modulo_bias() {
        // 62 不整除 256：朴素取模会让前 4 个字符明显更常见。拒绝采样下应大体均匀。
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        val counts = HashMap<Char, Int>()
        val total = 62 * 400
        for (c in SecureRandom.string(total, alphabet)) counts[c] = (counts[c] ?: 0) + 1
        assertEquals(alphabet.length, counts.size, "未覆盖全部字符：${counts.size}")
        val expected = total / alphabet.length          // 400
        // 宽松边界：只为抓住系统性偏置（如取模偏置约 +1.6%），不做严格统计检验
        for ((c, n) in counts) {
            assertTrue(n > expected / 2, "字符 '$c' 出现 $n 次，远低于期望 $expected")
            assertTrue(n < expected * 2, "字符 '$c' 出现 $n 次，远高于期望 $expected")
        }
    }
}
