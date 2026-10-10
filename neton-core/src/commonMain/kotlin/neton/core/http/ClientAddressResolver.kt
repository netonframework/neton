package neton.core.http

/** Installed once by routing; the request view memoizes its result for all policy consumers. */
fun interface ClientAddressResolver {
    fun resolve(request: HttpRequest): String
}
