package neton.http

import neton.core.Neton
import neton.core.component.HttpConfig
import neton.http.hyper4k.Hyper4kHttpAdapter

/**
 * The no-argument entry point: `http { port = 8080 }`.
 *
 * It lives here rather than in neton-http so the framework never references an
 * engine. Depending on this module is what makes hyper4k the default; switching
 * engines stays explicit, `http(::KtorHttpAdapter) { }`.
 *
 * Declared in package `neton.http` on purpose: applications already import the
 * two-argument overload from there, so adopting the default costs a build file
 * change and no source change.
 *
 * Only one module may own this overload. The Ktor module deliberately does not
 * ship one, or an application depending on both would see a duplicate declaration.
 */
fun Neton.LaunchBuilder.http(block: HttpConfig.() -> Unit = {}) {
    http(::Hyper4kHttpAdapter, block)
}
