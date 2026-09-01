// neton-ai/src/commonTest/kotlin/neton/ai/AiComponentBootTest.kt
package neton.ai

import neton.http.client.create

import kotlinx.coroutines.test.runTest
import neton.core.component.NetonContext
import neton.http.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests AiComponent.init() behavior using a real NetonContext.
 *
 * NOTE: this test IS allowed to import neton.core (it tests the Mode 2 component, not Mode 1).
 *
 * NetonContext is a concrete class (not an interface), so we use it directly with a
 * no-provider constructor: NetonContext(emptyArray()).
 */
class AiComponentBootTest {

    @Test fun initFailsWithClearMessageWhenHttpClientMissing() = runTest {
        val ctx = NetonContext(emptyArray())  // no HttpClient bound
        val ex = assertFailsWith<AiException> {
            AiComponent.init(ctx, AiConfig().apply {
                providers { openAiCompatible("openai") { baseUrl = "https://x"; apiKey = "k" } }
                routing { defaultModel = "openai:m" }
            })
        }
        assertTrue(ex.error is AiError.InvalidRequest)
        assertTrue("HTTP client" in ex.error.message,
            "error must direct user to install the HTTP client first; was: ${ex.error.message}")
    }

    @Test fun initBindsAiClientWhenConfigValid() = runTest {
        val ctx = NetonContext(emptyArray())
        val httpClient = HttpClient.create()
        ctx.bind(HttpClient::class, httpClient)
        AiComponent.init(ctx, AiConfig().apply {
            providers { openAiCompatible("openai") { baseUrl = "https://x"; apiKey = "k" } }
            routing { defaultModel = "openai:m" }
        })
        val ai = ctx.getOrNull(AiClient::class)
        assertNotNull(ai, "AiClient should be bound after init")
        httpClient.close()
    }
}
