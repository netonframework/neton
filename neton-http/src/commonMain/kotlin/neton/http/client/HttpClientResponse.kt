package neton.http.client

import neton.core.http.HttpHeaders

/**
 * Non-streaming response. For large bodies / SSE use HttpClient.stream() instead.
 */
data class HttpClientResponse(
    val statusCode: Int,
    val headers: HttpHeaders,
    val body: String,
)
