// neton-ai/src/commonTest/kotlin/neton/ai/usage/RedactionVerificationTest.kt
package neton.ai.usage

import neton.ai.testkit.jsonResponse
import neton.http.client.HttpClientMethod
import neton.http.testkit.ScriptedHttpClient

import kotlinx.coroutines.test.runTest
import neton.ai.AiClient
import neton.http.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RedactionVerificationTest {

    @Test fun debugLogsDoNotContainApiKey() = runTest {
        val capturedLines = mutableListOf<String>()
        val engine = ScriptedHttpClient().on(HttpClientMethod.Post, "") { _ ->
            jsonResponse(200, """{"choices":[{"message":{"role":"assistant","content":"x"},"finish_reason":"stop"}]}""")}
        val ai = AiClient.create {
            httpClient = engine
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
        val engine = ScriptedHttpClient().on(HttpClientMethod.Post, "") { _ ->
            jsonResponse(200, """{"choices":[{"message":{"role":"assistant","content":"x"},"finish_reason":"stop"}]}""")}
        val ai = AiClient.create {
            httpClient = engine
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
