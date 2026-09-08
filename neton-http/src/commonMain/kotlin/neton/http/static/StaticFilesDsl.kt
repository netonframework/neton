package neton.http.static

import neton.core.http.HandlerArgs
import neton.core.http.HttpContext
import neton.core.http.HttpMethod
import neton.core.interfaces.RequestEngine
import neton.core.interfaces.RouteDefinition
import neton.core.interfaces.RouteHandler

/**
 * Mounts a directory at [urlPrefix], serving files under [directory]. Works the
 * same whether written as plain code (`routes.staticFiles(...)`) or inside a
 * `routing { }` block, because both run with a [RequestEngine] receiver.
 *
 *     routing { staticFiles("/assets", "./public") }
 *
 * The mount registers GET and HEAD catch-all routes; a more specific handler on
 * the same prefix still wins. See [StaticFileServer] for the HTTP semantics and
 * the security rules.
 */
fun RequestEngine.staticFiles(
    urlPrefix: String,
    directory: String,
    configure: (StaticFilesConfig.() -> Unit)? = null,
) {
    val config = StaticFilesConfig().apply { configure?.invoke(this) }
    val server = StaticFileServer(directory, config)
    val prefix = "/" + urlPrefix.trim('/')
    val pattern = if (prefix == "/") "/{path...}" else "$prefix/{path...}"

    val handler = object : RouteHandler {
        override suspend fun invoke(context: HttpContext, args: HandlerArgs): Any? {
            server.serve(context, context.request.pathParam("path") ?: "")
            return Unit
        }
    }
    for (method in listOf(HttpMethod.GET, HttpMethod.HEAD)) {
        registerRoute(
            RouteDefinition(
                pattern = pattern,
                method = method,
                handler = handler,
                allowAnonymous = true,
            ),
        )
    }
}
