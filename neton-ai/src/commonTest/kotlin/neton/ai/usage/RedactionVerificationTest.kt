// neton-ai/src/commonTest/kotlin/neton/ai/usage/RedactionVerificationTest.kt
package neton.ai.usage

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import neton.ai.AiClient
import neton.http.client.NetonHttpClient
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RedactionVerificationTest {

    private fun factoryOf(engine: MockEngine) = object : HttpClientEngineFactory<MockEngineConfig> {
        override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = engine
    }

    @Test fun debugLogsDoNotContainApiKey() = runTest {
        val capturedLines = mutableListOf<String>()
        val engine = MockEngine { _ ->
            respond(
                """{"choices":[{"message":{"role":"assistant","content":"x"},"finish_reason":"stop"}]}""",
                HttpStatusCode.OK,
                headersOf("Content-Type", "application/json"),
            )
        }
        val ai = AiClient.create {
            httpClient = NetonHttpClient.createWithEngine(factoryOf(engine))
            providers {
                openAiCompatible("openai") {
                    baseUrl = "https://api.example.com/v1"
                    apiKey = "sk-SECRET-DO-NOT-LEAK"
                }
            }
            routing { defaultModel = "openai:m" }
            debug = true
            logSink = { line -> capturedLines += line }
        }
        ai.generateText { user("hi") }
        ai.close()

        // The API key value MUST NOT appear anywhere in captured logs
        for (line in capturedLines) {
            assertFalse("sk-SECRET-DO-NOT-LEAK" in line,
                "API key leaked in log line: $line")
        }
        // At least one log line was emitted (verifies logSink is actually wired)
        assertTrue(capturedLines.isNotEmpty(), "expected at least one debug log line; logSink may not be wired")
        // The Authorization header should appear as <redacted>
        assertTrue(capturedLines.any { "Authorization" in it && "<redacted>" in it },
            "Expected Authorization=<redacted> in some log line; lines: $capturedLines")
    }

    @Test fun debugFalseEmitsNoLogs() = runTest {
        val capturedLines = mutableListOf<String>()
        val engine = MockEngine { _ ->
            respond("""{"choices":[{"message":{"role":"assistant","content":"x"},"finish_reason":"stop"}]}""",
                HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }
        val ai = AiClient.create {
            httpClient = NetonHttpClient.createWithEngine(factoryOf(engine))
            providers { openAiCompatible("openai") { baseUrl = "https://x"; apiKey = "k" } }
            routing { defaultModel = "openai:m" }
            debug = false
            logSink = { line -> capturedLines += line }
        }
        ai.generateText { user("hi") }
        ai.close()
        assertTrue(capturedLines.isEmpty(), "no logs expected when debug=false; got: $capturedLines")
    }
}
