package neton.http.client

/**
 * Wrapper exception so NetonHttpError (sealed interface) can be thrown across coroutines / suspend functions.
 * The neton-http client API throws this; downstream consumers map [error] to their own taxonomy.
 */
class NetonHttpException(val error: NetonHttpError) : RuntimeException(error.message, error.cause)
