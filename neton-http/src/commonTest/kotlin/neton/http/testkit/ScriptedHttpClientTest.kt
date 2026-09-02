package neton.http.testkit

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import neton.core.http.HttpHeaders
import neton.http.client.HttpClientCapability
import neton.http.client.HttpClientError
import neton.http.client.HttpClientException
import neton.http.client.HttpClientMethod
import neton.http.client.HttpClientRequest
import neton.http.client.HttpClientResponse
import neton.http.client.HttpClientStreamChunk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ScriptedHttpClientTest {

    private fun get(url: String) = HttpClientRequest(HttpClientMethod.Get, url)

    @Test
    fun answersTheFirstMatchingScriptAndRecordsTheRequest() = runTest {
        val client = ScriptedHttpClient()
            .on(HttpClientMethod.Get, "https://api.example/v1/") { HttpClientResponse(200, HttpHeaders.EMPTY, "v1") }
            .on(HttpClientMethod.Get, "https://api.example/") { HttpClientResponse(200, HttpHeaders.EMPTY, "root") }

        assertEquals("v1", client.request(get("https://api.example/v1/models")).body)
        assertEquals("root", client.request(get("https://api.example/other")).body)
        assertEquals(listOf("https://api.example/v1/models", "https://api.example/other"), client.recorded.map { it.url })
    }

    @Test
    fun anUnregisteredRequestFailsLoudlyInsteadOfReturningAnEmptyResponse() = runTest {
        val client = ScriptedHttpClient().on(HttpClientMethod.Post, "https://a/") { HttpClientResponse(200, HttpHeaders.EMPTY, "") }
        val e = assertFailsWith<HttpClientException> { client.request(get("https://a/")) }
        assertIs<HttpClientError.Network>(e.error)
        assertTrue("Post https://a/" in e.error.message, e.error.message)
    }

    @Test
    fun streamScriptsReplayTheirChunks() = runTest {
        val client = ScriptedHttpClient().onStream(
            HttpClientMethod.Get, "https://s/",
            listOf(HttpClientStreamChunk.Text("a"), HttpClientStreamChunk.Text("b"), HttpClientStreamChunk.End(HttpHeaders.EMPTY)),
        )
        val chunks = client.stream(get("https://s/events")).toList()
        assertEquals(3, chunks.size)
        assertIs<HttpClientStreamChunk.End>(chunks.last())
    }

    @Test
    fun usingTheWrongEntryPointForAScriptIsAnError() = runTest {
        val client = ScriptedHttpClient()
            .onStream(HttpClientMethod.Get, "https://s/", listOf(HttpClientStreamChunk.End(HttpHeaders.EMPTY)))
        val e = assertFailsWith<HttpClientException> { client.request(get("https://s/")) }
        assertTrue("stream()" in e.error.message, e.error.message)
    }

    @Test
    fun closeIsObservableAndRejectsFurtherUse() = runTest {
        val client = ScriptedHttpClient().on(HttpClientMethod.Get, "https://a/") { HttpClientResponse(200, HttpHeaders.EMPTY, "") }
        client.close()
        assertTrue(client.isClosed)
        assertFailsWith<HttpClientException> { client.request(get("https://a/")) }
    }

    @Test
    fun declaresEveryCapabilityBecauseItIsNotAnEngine() {
        assertEquals(HttpClientCapability.entries.toSet(), ScriptedHttpClient().capabilities)
    }
}
