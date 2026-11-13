package neton.core.http

import neton.core.component.NetonContext

/** Static metadata only; [pattern] includes the route group's mount. */
data class RouteEndpoint(val method: HttpMethod, val pattern: String, val group: String?, val routePattern: String)

data class RouteSelector(
    val group: String? = null,
    val method: HttpMethod? = null,
    val pattern: String? = null,
) {
    fun matches(endpoint: RouteEndpoint): Boolean =
        (group == null || group == endpoint.group) &&
            (method == null || method == endpoint.method) &&
            (pattern == null || pattern == endpoint.pattern)
}

fun interface RouteInterceptor {
    /** Call [next] at most once, inline in this request; never hand it to a background task. */
    suspend fun intercept(context: HttpContext, next: suspend () -> Any?): Any?
}

enum class RoutePhase { ADMISSION, SECURITY, RATE_LIMIT, UPGRADE, AROUND, HANDLER, RESPONSE }
enum class RouteResult { RESPONDED, REJECTED, FAILED, CANCELLED }

/** No mutable context, raw request data or response body. Not a transport-delivery event. */
data class RouteCompletion(
    val endpoint: RouteEndpoint,
    val invocationId: String,
    val actorId: String?,
    val clientAddress: String,
    val durationMillis: Long,
    val phase: RoutePhase,
    val result: RouteResult,
    val httpStatus: Int?,
    val errorCode: Int?,
    val handlerEntered: Boolean,
)

fun interface RouteCompletionObserver {
    /** Runs synchronously; enqueue bounded work rather than doing I/O here. */
    fun observe(completion: RouteCompletion)
}

/** Invocation ID exists only on routes with a completion observer. Never derived from a header. */
object RouteLifecycleAttributes {
    const val INVOCATION_ID = "neton.route.invocation-id"
    const val ENDPOINT = "neton.route.endpoint"
}

/** Bootstrap-only registration, independent of RouteDefinition and its generated constructors. */
class RouteLifecycle(private val context: NetonContext) {
    private val around = mutableListOf<Entry<RouteInterceptor>>()
    private val completion = mutableListOf<Entry<RouteCompletionObserver>>()
    private val ids = mutableSetOf<String>()

    fun intercept(id: String, interceptor: RouteInterceptor, selector: RouteSelector = RouteSelector(), order: Int = 0) =
        add(around, Entry(id, selector, order, interceptor))

    fun observe(id: String, observer: RouteCompletionObserver, selector: RouteSelector = RouteSelector(), order: Int = 0) =
        add(completion, Entry(id, selector, order, observer))

    fun interceptors(endpoint: RouteEndpoint): List<RouteInterceptor> = select(around, endpoint)
    fun observers(endpoint: RouteEndpoint): List<RouteCompletionObserver> = select(completion, endpoint)

    private fun <T> add(entries: MutableList<Entry<T>>, entry: Entry<T>) {
        check(!context.isFrozen) { "Route lifecycle registration is closed" }
        require(entry.id.isNotBlank() && ids.add(entry.id)) { "Invalid or duplicate route extension: ${entry.id}" }
        entries.add(entry)
    }

    private fun <T> select(entries: List<Entry<T>>, endpoint: RouteEndpoint): List<T> = entries.asSequence()
        .filter { it.selector.matches(endpoint) }
        .sortedWith(compareBy({ it.order }, { it.id }))
        .map { it.extension }
        .toList()

    private data class Entry<T>(val id: String, val selector: RouteSelector, val order: Int, val extension: T)
}

fun NetonContext.routeLifecycle(): RouteLifecycle =
    getOrNull(RouteLifecycle::class) ?: RouteLifecycle(this).also { bind(RouteLifecycle::class, it) }
