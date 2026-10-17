package neton.security.password

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

class PasswordHasherTest {

    @Test
    fun hashAndVerifyCurrentFormat() {
        val hash = PasswordHasher.hash("CorrectHorseBatteryStaple!")

        val result = PasswordHasher.verify("CorrectHorseBatteryStaple!", hash)

        assertTrue(result.verified)
        assertFalse(result.needsRehash)
    }

    @Test
    fun differentHashesForSamePassword() {
        val first = PasswordHasher.hash("same-password")
        val second = PasswordHasher.hash("same-password")

        assertNotEquals(first, second)
        assertTrue(PasswordHasher.verify("same-password", first).verified)
        assertTrue(PasswordHasher.verify("same-password", second).verified)
    }

    @Test
    fun verifyLegacyHashAndMarkForRehash() {
        val legacyHash = "dcf97c4b4415986cad61148a408e87b17968bfe4904cda4d64b5e17064d1a0f8"

        val result = PasswordHasher.verify("admin123", legacyHash)

        assertTrue(result.verified)
        assertTrue(result.needsRehash)
    }

    /** RFC 7914 §11 的 PBKDF2-HMAC-SHA256 测试向量，按存储格式编码后校验。 */
    @Test
    fun verifiesRfc7914Vectors() {
        val c1 = "pbkdf2-sha256\$1\$c2FsdA\$VawEblbjCJ_sFpHCJUS2BflBhSFt3gRl5oudV8INrLxJypzM8Xm2RZkWZLOdd-8xfHG4RbHjC9UJESBB06GXgw"
        val c80000 = "pbkdf2-sha256\$80000\$TmFDbA\$TdzY9guYviGDDO5e8icB-WQaRBjQTAQUrv8Ih2s0q1ah1CWhIlgzVJrbhBtRybMXaicr3ruh0HhHj2Kzl_M8jQ"

        assertTrue(PasswordHasher.verify("passwd", c1).verified)
        assertTrue(PasswordHasher.verify("passwd", c1).needsRehash)
        assertTrue(PasswordHasher.verify("Password", c80000).verified)
        assertFalse(PasswordHasher.verify("password", c80000).verified)
    }

    /** 旧手写循环在 210 000 轮下产出的哈希（同一盐与口令），切到原生实现后仍可校验，不需要迁移。 */
    @Test
    fun verifiesHashesWrittenByThePreviousImplementation() {
        val stored = "pbkdf2-sha256\$210000\$AAECAwQFBgcICQoLDA0ODw\$y61rG0jEh0yeptMjVSQb9oKw8CzThzijQi2GEgtlnBo"

        val result = PasswordHasher.verify("CorrectHorseBatteryStaple!", stored)

        assertTrue(result.verified)
        assertFalse(result.needsRehash)
    }

    /**
     * 回归护栏：一次校验曾因逐轮重新导入 HMAC 密钥耗时约 1.7 秒（macOS arm64 debug）。原生 PBKDF2
     * 约 34 毫秒；上限放宽到 1 秒，只拦「又退回逐轮循环」这种数量级退化。
     */
    @Test
    fun verifyCostStaysInTheNativeRange() {
        val hash = PasswordHasher.hash("timing")
        PasswordHasher.verify("warm-up", hash)

        val elapsed = measureTime { PasswordHasher.verify("wrong", hash) }

        assertTrue(elapsed < 1.seconds, "PBKDF2 verify took $elapsed")
    }
}
