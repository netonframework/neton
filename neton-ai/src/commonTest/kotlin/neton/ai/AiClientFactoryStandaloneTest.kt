// neton-ai/src/commonTest/kotlin/neton/ai/AiClientFactoryStandaloneTest.kt
//
// CONTRACT GUARDRAIL: Mode 1 dual-usage. MUST NOT import:
//   - neton.core.*
//   - neton.logging.*
//   - neton.ai.internal.*    (internal types)
//   - neton.http.client.internal.*
//
// If this test ever needs Neton runtime imports, the standalone-usage contract is broken.
package neton.ai

import neton.http.client.create
import neton.http.client.createWithEngine

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import neton.http.client.NetonHttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AiClientFactoryStandaloneTest {

    private fun factoryOf(engine: MockEngine) = object : HttpClientEngineFactory<MockEngineConfig> {
        override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = engine
    }

    @Test fun createWithMinimalConfigSucceeds() = runTest {
        val engine = MockEngine { _ ->
            respond(
                """{"choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}""",
                HttpStatusCode.OK,
                headersOf("Content-Type", "application/json"),
            )
        }
        val ai = AiClient.create {
            httpClient = NetonHttpClient.createWithEngine(factoryOf(engine))
            providers {
                openAiCompatible("openai") {
                    baseUrl = "https://api.example.com/v1"
                    apiKey = "sk-test"
                }
            }
            routing { defaultModel = "openai:gpt-4o-mini" }
        }
        val result = ai.generateText {
            user("hi")
        }
        assertEquals("ok", result.text)
        assertEquals("openai", result.providerId)
        ai.close()
    }

    @Test fun missingHttpClientFailsValidate() {
        val ex = assertFailsWith<AiException> {
            AiClient.create {
                providers { openAiCompatible("openai") { baseUrl = "https://x"; apiKey = "k" } }
                routing { defaultModel = "openai:m" }
            }
        }
        assertTrue(ex.error is AiError.InvalidRequest)
        assertTrue("httpClient" in ex.error.message)
    }

    @Test fun missingProvidersFailsValidate() {
        val ex = assertFailsWith<AiException> {
            AiClient.create {
                httpClient = NetonHttpClient.create()
                routing { defaultModel = "x:y" }
            }
        }
        assertTrue(ex.error is AiError.InvalidRequest)
    }

    @Test fun routingReferencingUnknownProviderFails() {
        val ex = assertFailsWith<AiException> {
            AiClient.create {
                httpClient = NetonHttpClient.create()
                providers { openAiCompatible("openai") { baseUrl = "https://x"; apiKey = "k" } }
                routing { defaultModel = "missing:m" }
            }
        }
        assertTrue(ex.error is AiError.InvalidRequest)
        assertTrue("missing" in ex.error.message)
    }

    @Test fun dualProviderSetupBuilds() = runTest {
        val ai = AiClient.create {
            httpClient = NetonHttpClient.create()
            providers {
                openAiCompatible("openai") { baseUrl = "https://x"; apiKey = "k" }
                anthropic("anthropic") { apiKey = "a" }
            }
            routing {
                defaultModel = "openai:m"
                policy("strong") {
                    prefer("anthropic:claude-sonnet-4.5")
                    fallback("openai:gpt-4o")
                }
            }
        }
        // Don't actually call; just verify the AiClient was built successfully.
        ai.close()
    }
}
