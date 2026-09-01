package neton.http.client

import neton.core.http.HttpHeaders

/**
 * Non-streaming response. For large bodies / SSE use NetonHttpClient.stream() instead.
 */
data class NetonHttpResponse(
    val statusCode: Int,
    val headers: HttpHeaders,
    val body: String,
)
