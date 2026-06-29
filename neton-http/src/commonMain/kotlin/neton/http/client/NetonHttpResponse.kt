package neton.http.client

/**
 * Non-streaming response. For large bodies / SSE use NetonHttpClient.stream() instead.
 */
data class NetonHttpResponse(
    val statusCode: Int,
    val headers: Map<String, String>,
    val body: String,
)
