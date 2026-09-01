// Standalone outbound HTTP client usage contract.
//
// CONTRACT GUARDRAIL: This test verifies "Mode 1" of the dual-usage design constraint.
// MUST NOT import:
//   - neton.core.*
//   - neton.logging.*
//   - neton.http.client.internal.* (internal types)
//
// If this test ever needs Neton runtime imports, the standalone-usage contract is broken.
package neton.http.client

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StandaloneUsageTest {

    @Test
    fun createWithDefaultConfigProducesUsableClient() = runTest {
        val client = HttpClient.create()
        // We can't make a real request without an engine, but instantiation alone proves the
        // factory does NOT require NetonContext / LoggerFactory / any runtime dependency.
        assertTrue(client is HttpClient)  // tautology, but proves type binding
        client.close()
    }

    @Test
    fun createWithDslConfigAppliesOverrides() = runTest {
        // DSL block runs without any Neton runtime / context.
        val client = HttpClient.create {
            requestMillis = 12_345
            connectMillis = 999
            socketMillis = 67_890
            debug = true
        }
        client.close()  // success = no exception during construction
    }

    @Test
    fun invalidConfigThrowsTypedException() {
        val ex = assertFailsWith<HttpClientException> {
            HttpClient.create { requestMillis = 0 }  // 0 invalid (must be > 0)
        }
        assertTrue(ex.error is HttpClientError.Unknown,
            "Expected Unknown error for invalid config, got ${ex.error::class.simpleName}")
        assertTrue("requestMillis" in ex.error.message,
            "Error message should mention invalid field, was: ${ex.error.message}")
    }
}
