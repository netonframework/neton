package neton.http.client

/**
 * HTTP-layer retry primitive. v0.1 ships only NoRetryPolicy.
 * Downstream modules (e.g., neton-ai router fallback) handle their own retry semantics.
 */
interface NetonRetryPolicy {
    fun shouldRetry(attempt: Int, response: NetonHttpResponse?, error: NetonHttpError?): RetryDecision
}

sealed interface RetryDecision {
    data object DoNotRetry : RetryDecision
    data class RetryAfter(val delayMillis: Long) : RetryDecision
}

object NoRetryPolicy : NetonRetryPolicy {
    override fun shouldRetry(attempt: Int, response: NetonHttpResponse?, error: NetonHttpError?): RetryDecision =
        RetryDecision.DoNotRetry
}
