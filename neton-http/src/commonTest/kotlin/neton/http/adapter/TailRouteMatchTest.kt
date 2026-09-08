package neton.http.adapter

import neton.core.component.NetonContext
import neton.core.http.HandlerArgs
import neton.core.http.HttpContext
import neton.core.http.HttpMethod
import neton.core.interfaces.RequestEngine
import neton.core.interfaces.RouteDefinition
import neton.core.interfaces.RouteHandler
import neton.core.http.adapter.HttpServerConfig
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Catch-all `{name...}` routes: they match at any depth, bind the remainder, and
 * always lose to a more specific route so a mount never shadows a real handler.
 */
class TailRouteMatchTest {

    private fun handler(tag: String) = object : RouteHandler {
        override suspend fun invoke(context: HttpContext, args: HandlerArgs): Any {
            val rest = args.first("path") ?: ""
            context.response.write("$tag:$rest".encodeToByteArray())
            return Unit
        }
    }

    private fun dispatcher(vararg routes: RouteDefinition): BufferedHttpDispatcher {
        val ctx = NetonContext(emptyArray()).apply {
            bind(RequestEngine::class, object : RequestEngine {
                override fun registerRoute(route: RouteDefinition) = Unit
                override fun getRoutes() = routes.toList()
            })
        }
        return BufferedHttpDispatcher(HttpServerConfig(port = 0)).also { it.bind(ctx) }
    }

    private fun req(path: String) = BufferedHttpRequest("GET", path, "", emptyMap(), ByteArray(0))
    private suspend fun body(d: BufferedHttpDispatcher, path: String) =
        d.dispatch(req(path)).body.decodeToString()

    private fun tail(pattern: String, tag: String) =
        RouteDefinition(pattern = pattern, method = HttpMethod.GET, allowAnonymous = true, handler = handler(tag))

    @Test
    fun matchesAtAnyDepthAndBindsRemainder() = runBlocking {
        val d = dispatcher(tail("/assets/{path...}", "A"))
        assertEquals("A:css/theme.css", body(d, "/assets/css/theme.css"))
        assertEquals("A:logo.png", body(d, "/assets/logo.png"))
        assertEquals("A:", body(d, "/assets"))
    }

    @Test
    fun aMoreSpecificRouteWins() = runBlocking {
        val exact = RouteDefinition("/assets/special", HttpMethod.GET, allowAnonymous = true, handler = handler("EXACT"))
        val d = dispatcher(tail("/assets/{path...}", "TAIL"), exact)
        assertEquals("EXACT:", body(d, "/assets/special"))
        assertEquals("TAIL:other.css", body(d, "/assets/other.css"))
    }

    @Test
    fun longerMountPrefixWinsAmongTailRoutes() = runBlocking {
        val d = dispatcher(tail("/a/{path...}", "SHORT"), tail("/a/b/{path...}", "LONG"))
        assertEquals("LONG:c.txt", body(d, "/a/b/c.txt"))
        assertEquals("SHORT:x.txt", body(d, "/a/x.txt"))
    }

    @Test
    fun percentEncodedSegmentsAreDecodedInTheRemainder() = runBlocking {
        val d = dispatcher(tail("/f/{path...}", "F"))
        assertEquals("F:a b/c.txt", body(d, "/f/a%20b/c.txt"))
    }
}
