package neton.ai.routing

import neton.ai.AiError
import neton.ai.AiException
import neton.ai.AiModelId
import neton.ai.internal.DefaultModelRouter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DefaultModelRouterTest {

    private fun router(default: String? = null, policies: Map<String, ModelPolicy> = emptyMap()) =
        DefaultModelRouter(RoutingConfig(
            defaultModel = default?.let(AiModelId::parse),
            policies = policies,
        ))

    @Test fun explicitModelWinsAndIsSingleCandidate() {
        val r = router(default = "openai:gpt-4o-mini")
        val candidates = r.resolve(AiModelId.parse("anthropic:claude-sonnet-4.5"), null)
        assertEquals(listOf(AiModelId("anthropic", "claude-sonnet-4.5")), candidates)
    }

    @Test fun policyPreferThenFallbackInOrder() {
        val r = router(policies = mapOf("strong" to ModelPolicy(
            prefer = listOf(AiModelId.parse("anthropic:claude-sonnet-4.5")),
            fallback = listOf(AiModelId.parse("openai:gpt-4o"), AiModelId.parse("deepseek:deepseek-chat")),
        )))
        val candidates = r.resolve(null, "strong")
        assertEquals(listOf(
            AiModelId("anthropic", "claude-sonnet-4.5"),
            AiModelId("openai", "gpt-4o"),
            AiModelId("deepseek", "deepseek-chat"),
        ), candidates)
    }

    @Test fun defaultModelUsedWhenNeitherExplicitNorPolicy() {
        val r = router(default = "openai:gpt-4o-mini")
        val candidates = r.resolve(null, null)
        assertEquals(listOf(AiModelId("openai", "gpt-4o-mini")), candidates)
    }

    @Test fun missingConfigThrowsInvalidRequest() {
        val r = router()
        val ex = assertFailsWith<AiException> { r.resolve(null, null) }
        assertTrue(ex.error is AiError.InvalidRequest)
    }

    @Test fun unknownPolicyThrowsInvalidRequest() {
        val r = router(default = "openai:gpt-4o-mini")
        val ex = assertFailsWith<AiException> { r.resolve(null, "nope") }
        assertTrue(ex.error is AiError.InvalidRequest)
        assertTrue("nope" in ex.error.message)
    }

    @Test fun emptyPolicyPreferThrowsInvalidRequest() {
        val r = router(policies = mapOf("empty" to ModelPolicy(prefer = emptyList(), fallback = emptyList())))
        val ex = assertFailsWith<AiException> { r.resolve(null, "empty") }
        assertTrue(ex.error is AiError.InvalidRequest)
    }

    @Test fun explicitModelDoesNotFallBackToDefault() {
        val r = router(default = "openai:gpt-4o-mini")
        val candidates = r.resolve(AiModelId.parse("custom:weird"), null)
        assertEquals(1, candidates.size)
        assertEquals(AiModelId("custom", "weird"), candidates[0])
    }
}
