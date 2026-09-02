package neton.http.client

/**
 * Marker type for the no-engine fallback overloads. Never used by application
 * code; it exists so those overloads take one more parameter than the engine
 * modules' entry points and lose overload resolution whenever an engine is
 * present (spec zh-hans/spec/http-engine.md §4.3).
 */
object MissingHttpEngine

/**
 * Chosen only when no engine module declares `HttpClient.create(block)`. Kotlin
 * prefers the candidate that needs no default arguments, so with an engine on the
 * classpath this overload is never selected; without one, the call resolves here
 * and the deprecation error below is the compile error the developer sees,
 * instead of "Unresolved reference 'create'".
 */
@Deprecated(
    message = "No HTTP engine on the classpath. HttpClient.create { } is provided by an engine module: " +
        "add com.netonstream:neton-http-hyper4k, or depend on com.netonstream:neton which includes it.",
    level = DeprecationLevel.ERROR,
)
@Suppress("UNUSED_PARAMETER")
fun HttpClient.Companion.create(
    // First, not last: a trailing lambda binds to the final parameter, and it
    // must land on `block` so the only diagnostic is the message above.
    missingEngine: MissingHttpEngine = MissingHttpEngine,
    block: HttpClientConfig.() -> Unit = {},
): HttpClient = throw IllegalStateException("No HTTP engine on the classpath")
