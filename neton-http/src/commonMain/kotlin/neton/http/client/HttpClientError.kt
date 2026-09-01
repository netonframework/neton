package neton.http.client

sealed interface HttpClientError {
    val message: String
    val cause: Throwable?

    data class Network(
        override val message: String,
        override val cause: Throwable?,
    ) : HttpClientError

    data class Timeout(
        override val message: String,
        override val cause: Throwable?,
    ) : HttpClientError

    /** HTTP-level error (4xx / 5xx). Body is optional (may be unavailable on streaming responses). */
    data class Http(
        val statusCode: Int,
        override val message: String,
        val body: String?,
    ) : HttpClientError {
        override val cause: Throwable? = null
    }

    data class Unknown(
        override val message: String,
        override val cause: Throwable?,
    ) : HttpClientError
}
