package neton.redis

import kotlin.test.*

class CredentialDisplayTest {
    @Test
    fun passwordIsNotRendered() {
        val config = RedisConfig(password = "display-test-secret")
        assertFalse(config.toString().contains("display-test-secret"))
        assertFalse(config.copy().toString().contains("display-test-secret"))
    }
}
