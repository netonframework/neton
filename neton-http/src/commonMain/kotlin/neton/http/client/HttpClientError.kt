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

    /**
     * A non-2xx response where the API surfaces it as an error (`stream()`). Body is optional (may be
     * unavailable on streaming responses). [headers] are the response's header fields, so a caller that
     * follows redirects by hand can read `Location` off a 3xx.
     */
    data class Http(
        val statusCode: Int,
        override val message: String,
        val body: String?,
        val headers: neton.core.http.HttpHeaders = neton.core.http.HttpHeaders.EMPTY,
    ) : HttpClientError {
        override val cause: Throwable? = null
    }

    /**
     * The request target was refused before any connection was made: an [OutboundAddressPolicy]
     * on the client did not admit its scheme, port or address, or its host did not resolve.
     */
    data class TargetRejected(
        val reason: TargetRejection,
        override val message: String,
    ) : HttpClientError {
        override val cause: Throwable? = null
    }

    data class Unknown(
        override val message: String,
        override val cause: Throwable?,
    ) : HttpClientError
}
