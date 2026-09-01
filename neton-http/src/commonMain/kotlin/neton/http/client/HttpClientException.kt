package neton.http.client

/**
 * Wrapper exception so HttpClientError (sealed interface) can be thrown across coroutines / suspend functions.
 * The neton-http client API throws this; downstream consumers map [error] to their own taxonomy.
 */
class HttpClientException(val error: HttpClientError) : RuntimeException(error.message, error.cause)
