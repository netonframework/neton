// neton-ai/src/commonTest/kotlin/neton/ai/config/AiConfigMergeTest.kt
package neton.ai.config

import neton.ai.AiConfig
import neton.ai.AiModelId
import neton.ai.internal.applyFileMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AiConfigMergeTest {

    // Sample TOML-decoded map (what ConfigLoader produces for ai.conf)
    private fun fileMap() = mapOf<String, Any?>(
        "debug" to false,
        "providers" to mapOf<String, Any?>(
            "openai" to mapOf<String, Any?>(
                "type" to "openAiCompatible",
                "baseUrl" to "https://api.openai.com/v1",
                "apiKey" to "sk-from-file",
                "timeoutMillis" to 30_000L,
            ),
            "anthropic" to mapOf<String, Any?>(
                "type" to "anthropic",
                "apiKey" to "ak-from-file",
                "version" to "2023-06-01",
            ),
        ),
        "routing" to mapOf<String, Any?>(
            "defaultModel" to "openai:gpt-4o-mini",
            "policies" to mapOf<String, Any?>(
                "strong" to mapOf<String, Any?>(
                    "prefer" to listOf("anthropic:claude-sonnet-4.5"),
                    "fallback" to listOf("openai:gpt-4o"),
                ),
            ),
        ),
    )

    @Test fun fileOnlyMergeBuildsCompleteConfig() {
        val cfg = AiConfig().apply { applyFileMap(fileMap()) }
        assertEquals(2, cfg.providers.size)
        val openai = cfg.providers["openai"] as OpenAiCompatibleSpec
        assertEquals("https://api.openai.com/v1", openai.baseUrl)
        assertEquals("sk-from-file", openai.apiKey)
        assertEquals(30_000L, openai.timeoutMillis)
        val anthropic = cfg.providers["anthropic"] as AnthropicSpec
        assertEquals("ak-from-file", anthropic.apiKey)
        assertEquals("2023-06-01", anthropic.version)
        assertEquals(AiModelId("openai", "gpt-4o-mini"), cfg.routing.defaultModel)
        assertEquals(1, cfg.routing.policies.size)
    }

    @Test fun dslExplicitOverridesFileField() {
        // DSL sets timeoutMillis on openai BEFORE merge applies file values
        val cfg = AiConfig().apply {
            providers {
                openAiCompatible("openai") {
                    timeoutMillis = 120_000L  // DSL explicit
                    // baseUrl, apiKey unset — should come from file
                }
            }
            applyFileMap(fileMap())
        }
        val openai = cfg.providers["openai"] as OpenAiCompatibleSpec
        assertEquals(120_000L, openai.timeoutMillis, "DSL value preserved")
        assertEquals("https://api.openai.com/v1", openai.baseUrl, "file fills unset DSL field")
        assertEquals("sk-from-file", openai.apiKey)
    }

    @Test fun newProviderInFileMergedAlongsideDslProviders() {
        val cfg = AiConfig().apply {
            providers {
                openAiCompatible("deepseek") {
                    baseUrl = "https://api.deepseek.com"
                    apiKey = "ds-key"
                }
            }
            applyFileMap(fileMap())
        }
        assertEquals(3, cfg.providers.size, "DSL deepseek + file openai + file anthropic")
        assertTrue("deepseek" in cfg.providers)
        assertTrue("openai" in cfg.providers)
        assertTrue("anthropic" in cfg.providers)
    }

    @Test fun emptyMapDoesNotChangeDsl() {
        val cfg = AiConfig().apply {
            providers {
                openAiCompatible("openai") {
                    baseUrl = "https://x"
                    apiKey = "k"
                }
            }
            applyFileMap(emptyMap())
        }
        assertEquals(1, cfg.providers.size)
    }

    @Test fun fileWithUnknownProviderTypeIsSkippedWithErrorOnValidate() {
        val cfg = AiConfig().apply {
            applyFileMap(mapOf(
                "providers" to mapOf(
                    "weird" to mapOf("type" to "unknown_type", "apiKey" to "x"),
                ),
            ))
        }
        // applyFileMap silently skips unknown types (no exception); validation catches it
        assertTrue(cfg.providers.isEmpty(), "unknown provider type is not added")
    }
}
