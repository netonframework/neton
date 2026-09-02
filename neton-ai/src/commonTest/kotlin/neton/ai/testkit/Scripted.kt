package neton.ai.testkit

import neton.core.http.HttpHeaders
import neton.http.client.HttpClientBody
import neton.http.client.HttpClientError
import neton.http.client.HttpClientException
import neton.http.client.HttpClientRequest
import neton.http.client.HttpClientResponse
import neton.http.client.HttpClientStreamChunk

/** Shorthands for scripting provider responses without any HTTP engine. */

internal fun jsonResponse(status: Int, body: String): HttpClientResponse =
    HttpClientResponse(status, HttpHeaders.of("Content-Type" to "application/json"), body)

/** One SSE body as a real client would deliver it: bytes, then End. */
internal fun sseChunks(payload: String): List<HttpClientStreamChunk> = listOf(
    HttpClientStreamChunk.Bytes(payload.encodeToByteArray()),
    HttpClientStreamChunk.End(HttpHeaders.of("Content-Type" to "text/event-stream")),
)

/** What `stream()` does on a non-2xx status: fail before the first chunk. */
internal fun streamHttpError(status: Int, body: String): Nothing =
    throw HttpClientException(HttpClientError.Http(status, "HTTP $status", body))

internal fun HttpClientRequest.bodyText(): String? = when (val b = body) {
    null -> null
    is HttpClientBody.Json -> b.text
    is HttpClientBody.Text -> b.text
    is HttpClientBody.Bytes -> b.bytes.decodeToString()
}
