package neton.ai.internal

import kotlin.test.*

class CredentialDisplayTest {
    @Test
    fun effectiveCredentialsAndHeadersAreNotRendered() {
        val secret = "display-test-secret"
        val openai = EffectiveOpenAiCompatibleConfig("test", "https://example.invalid", secret,
            null, 1000, mapOf("Authorization" to secret))
        val anthropic = EffectiveAnthropicConfig("test", "https://example.invalid", secret,
            "version", emptyList(), 1000, mapOf("Authorization" to secret))
        for (value in listOf(openai, openai.copy(), anthropic, anthropic.copy())) {
            assertFalse(value.toString().contains(secret))
        }
    }
}
