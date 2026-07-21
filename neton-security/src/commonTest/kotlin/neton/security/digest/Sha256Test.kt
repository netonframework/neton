package neton.security.digest

import kotlin.test.Test
import kotlin.test.assertEquals

class Sha256Test {
    @Test
    fun knownVector() {
        // NIST 标准向量
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Sha256.hex("abc")
        )
    }

    @Test
    fun emptyString() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Sha256.hex("")
        )
    }
}
