package neton.http.client

/**
 * Wrapper exception so NetonHttpError (sealed interface) can be thrown across coroutines / suspend functions.
 * Public API of neton-http-client throws this; downstream consumers (e.g., neton-ai) catch it and map .error to their own error taxonomy.
 */
class NetonHttpException(val error: NetonHttpError) : RuntimeException(error.message, error.cause)
