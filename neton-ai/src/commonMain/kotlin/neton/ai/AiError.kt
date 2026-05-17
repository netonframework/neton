package neton.ai

sealed interface AiError {
    val message: String
    val cause: Throwable?

    data class Network(override val message: String, override val cause: Throwable?) : AiError
    data class Timeout(override val message: String, override val cause: Throwable?) : AiError
    data class RateLimited(val retryAfterMillis: Long?, override val message: String) : AiError {
        override val cause: Throwable? = null
    }
    data class ServerError(val statusCode: Int, override val message: String) : AiError {
        override val cause: Throwable? = null
    }
    data class Unauthorized(override val message: String) : AiError {
        override val cause: Throwable? = null
    }
    data class Forbidden(override val message: String) : AiError {
        override val cause: Throwable? = null
    }
    data class InvalidRequest(override val message: String) : AiError {
        override val cause: Throwable? = null
    }
    data class ContextLengthExceeded(override val message: String) : AiError {
        override val cause: Throwable? = null
    }
    data class ContentFilter(override val message: String) : AiError {
        override val cause: Throwable? = null
    }
    data class ModelNotFound(val modelId: String, override val message: String) : AiError {
        override val cause: Throwable? = null
    }
    data class Unknown(override val message: String, override val cause: Throwable?) : AiError
}

/** Stable string for telemetry (refactor-safe; do not use class.simpleName). */
val AiError.kind: String
    get() = when (this) {
        is AiError.Network -> "Network"
        is AiError.Timeout -> "Timeout"
        is AiError.RateLimited -> "RateLimited"
        is AiError.ServerError -> "ServerError"
        is AiError.Unauthorized -> "Unauthorized"
        is AiError.Forbidden -> "Forbidden"
        is AiError.InvalidRequest -> "InvalidRequest"
        is AiError.ContextLengthExceeded -> "ContextLengthExceeded"
        is AiError.ContentFilter -> "ContentFilter"
        is AiError.ModelNotFound -> "ModelNotFound"
        is AiError.Unknown -> "Unknown"
    }

/**
 * Router fallback eligibility:
 *  Network / Timeout / RateLimited / ServerError(5xx) → eligible
 *  everything else → not eligible (auth, semantic, ModelNotFound)
 */
fun AiError.isFallbackEligible(): Boolean = when (this) {
    is AiError.Network -> true
    is AiError.Timeout -> true
    is AiError.RateLimited -> true
    is AiError.ServerError -> statusCode in 500..599
    else -> false
}
