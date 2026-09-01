package neton.http.client

/**
 * HTTP-layer retry primitive. v0.1 ships only NoRetryPolicy.
 * Downstream modules (e.g., neton-ai router fallback) handle their own retry semantics.
 */
interface RetryPolicy {
    fun shouldRetry(attempt: Int, response: HttpClientResponse?, error: HttpClientError?): RetryDecision
}

sealed interface RetryDecision {
    data object DoNotRetry : RetryDecision
    data class RetryAfter(val delayMillis: Long) : RetryDecision
}

object NoRetryPolicy : RetryPolicy {
    override fun shouldRetry(attempt: Int, response: HttpClientResponse?, error: HttpClientError?): RetryDecision =
        RetryDecision.DoNotRetry
}
