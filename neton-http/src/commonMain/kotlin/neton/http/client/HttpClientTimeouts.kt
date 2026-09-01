package neton.http.client

/**
 * Per-request timeout overrides. Any field null = inherit client default.
 */
data class HttpClientTimeouts(
    val connectMillis: Long? = null,
    val requestMillis: Long? = null,
    val socketMillis: Long? = null,
)
