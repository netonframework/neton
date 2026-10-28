package neton.ai

/**
 * Carrier exception so AiError (sealed interface, not Throwable) can be thrown across coroutines.
 */
@neton.ai.ExperimentalNetonAiApi
class AiException(val error: AiError) : RuntimeException(error.message, error.cause)
