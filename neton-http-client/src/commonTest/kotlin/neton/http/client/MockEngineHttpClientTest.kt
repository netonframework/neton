// neton-http-client/src/commonTest/kotlin/neton/http/client/MockEngineHttpClientTest.kt
package neton.http.client

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import neton.http.client.internal.DefaultNetonHttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MockEngineHttpClientTest {

    @Test
    fun getRequestReturnsBodyAndStatus() = runTest {
        val engine = MockEngine { request ->
            assertEquals("https://api.example.com/v1/test", request.url.toString())
            assertEquals("GET", request.method.value)
            respond(
                content = """{"ok":true}""",
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", "application/json"),
            )
        }
        val client = DefaultNetonHttpClient(engineFactory = engineFactoryFor(engine))
        val resp = client.request(NetonHttpRequest(
            method = NetonHttpMethod.Get,
            url = "https://api.example.com/v1/test",
        ))
        assertEquals(200, resp.statusCode)
        assertEquals("""{"ok":true}""", resp.body)
        assertTrue(resp.headers["Content-Type"]?.contains("application/json") == true)
        client.close()
    }

    @Test
    fun postWithJsonBodySendsCorrectContentType() = runTest {
        var capturedBody: String? = null
        var capturedContentType: String? = null
        val engine = MockEngine { request ->
            capturedBody = request.body.toByteArray().decodeToString()
            capturedContentType = request.body.contentType.toString()
            respond("ok", HttpStatusCode.OK)
        }
        val client = DefaultNetonHttpClient(engineFactory = engineFactoryFor(engine))
        client.request(NetonHttpRequest(
            method = NetonHttpMethod.Post,
            url = "https://api.example.com/x",
            body = NetonHttpBody.Json("""{"k":1}"""),
        ))
        assertEquals("""{"k":1}""", capturedBody)
        assertTrue(capturedContentType?.startsWith("application/json") == true)
        client.close()
    }

    @Test
    fun headersForwardedToEngine() = runTest {
        var capturedAuth: String? = null
        val engine = MockEngine { request ->
            capturedAuth = request.headers["Authorization"]
            respond("ok", HttpStatusCode.OK)
        }
        val client = DefaultNetonHttpClient(engineFactory = engineFactoryFor(engine))
        client.request(NetonHttpRequest(
            method = NetonHttpMethod.Get,
            url = "https://api.example.com/x",
            headers = mapOf("Authorization" to "Bearer test-key"),
        ))
        assertEquals("Bearer test-key", capturedAuth)
        client.close()
    }

    @Test
    fun http500BodyAndStatusReturnedNotThrown() = runTest {
        // expectSuccess=false: 5xx returns response, not exception (caller decides)
        val engine = MockEngine { _ ->
            respond(
                content = """{"error":"upstream"}""",
                status = HttpStatusCode.InternalServerError,
            )
        }
        val client = DefaultNetonHttpClient(engineFactory = engineFactoryFor(engine))
        val resp = client.request(NetonHttpRequest(method = NetonHttpMethod.Get, url = "https://api.example.com/x"))
        assertEquals(500, resp.statusCode)
        assertEquals("""{"error":"upstream"}""", resp.body)
        client.close()
    }

    @Test
    fun networkErrorMapsToNetonHttpExceptionNetwork() = runTest {
        val engine = MockEngine { _ -> throw kotlinx.io.IOException("connection refused") }
        val client = DefaultNetonHttpClient(engineFactory = engineFactoryFor(engine))
        val ex = assertFailsWith<NetonHttpException> {
            client.request(NetonHttpRequest(method = NetonHttpMethod.Get, url = "https://api.example.com/x"))
        }
        assertTrue(ex.error is NetonHttpError.Network, "Expected Network error, got ${ex.error::class.simpleName}")
        client.close()
    }

    @Test
    fun streamNon2xxThrowsHttpErrorWithBodyBeforeAnyChunk() = runTest {
        // Regression: a 429 error body must NOT be streamed as chunks (it would parse as
        // zero SSE events and downstream mappers would emit a silent empty Completed).
        val errorBody = """{"error":{"message":"Rate limit exceeded","type":"rate_limit_error"}}"""
        val engine = MockEngine { _ ->
            respond(
                content = errorBody,
                status = HttpStatusCode.TooManyRequests,
                headers = headersOf("Content-Type", "application/json"),
            )
        }
        val client = DefaultNetonHttpClient(engineFactory = engineFactoryFor(engine))
        val ex = assertFailsWith<NetonHttpException> {
            client.stream(NetonHttpRequest(method = NetonHttpMethod.Get, url = "https://api.example.com/stream"))
                .collect { }
        }
        val err = ex.error
        assertTrue(err is NetonHttpError.Http, "Expected Http error, got ${err::class.simpleName}")
        assertEquals(429, err.statusCode)
        assertEquals(errorBody, err.body, "Error body must be captured for downstream classification")
        client.close()
    }

    @Test
    fun streamServerErrorThrowsHttpError() = runTest {
        val engine = MockEngine { _ ->
            respond(content = "upstream exploded", status = HttpStatusCode.InternalServerError)
        }
        val client = DefaultNetonHttpClient(engineFactory = engineFactoryFor(engine))
        val ex = assertFailsWith<NetonHttpException> {
            client.stream(NetonHttpRequest(method = NetonHttpMethod.Get, url = "https://api.example.com/stream"))
                .collect { }
        }
        val err = ex.error
        assertTrue(err is NetonHttpError.Http, "Expected Http error, got ${err::class.simpleName}")
        assertEquals(500, err.statusCode)
        client.close()
    }
}

/**
 * Helper: wrap a pre-built MockEngine instance as a factory.
 * MockEngine itself implements HttpClientEngine, but DefaultNetonHttpClient takes a Factory.
 */
private fun engineFactoryFor(engine: MockEngine): io.ktor.client.engine.HttpClientEngineFactory<io.ktor.client.engine.mock.MockEngineConfig> {
    return object : io.ktor.client.engine.HttpClientEngineFactory<io.ktor.client.engine.mock.MockEngineConfig> {
        override fun create(block: io.ktor.client.engine.mock.MockEngineConfig.() -> Unit): io.ktor.client.engine.HttpClientEngine = engine
    }
}
