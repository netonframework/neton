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
 * The method fast-path (match upper-case verbatim, fall back to uppercase() for
 * odd casing) must resolve the same routes as before. Driven through dispatch so
 * it exercises the real path, not just the private helper.
 */
class HttpMethodParseTest {
    private fun okRoute(m: HttpMethod) = RouteDefinition(
        pattern = "/x", method = m, allowAnonymous = true,
        handler = object : RouteHandler {
            override suspend fun invoke(context: HttpContext, args: HandlerArgs): Any {
                context.response.write(m.name.encodeToByteArray()); return Unit
            }
        },
    )
    private fun dispatcher(vararg m: HttpMethod): BufferedHttpDispatcher {
        val ctx = NetonContext(emptyArray()).apply {
            bind(RequestEngine::class, object : RequestEngine {
                override fun registerRoute(route: RouteDefinition) = Unit
                override fun getRoutes() = m.map { okRoute(it) }
            })
        }
        return BufferedHttpDispatcher(HttpServerConfig(port = 0)).also { it.bind(ctx) }
    }

    @Test
    fun upperCaseMethodsResolve() = runBlocking {
        val d = dispatcher(HttpMethod.GET, HttpMethod.POST, HttpMethod.DELETE)
        assertEquals(200, d.dispatch(BufferedHttpRequest("GET", "/x", "", emptyMap(), ByteArray(0))).status)
        assertEquals(200, d.dispatch(BufferedHttpRequest("POST", "/x", "", emptyMap(), ByteArray(0))).status)
        assertEquals(200, d.dispatch(BufferedHttpRequest("DELETE", "/x", "", emptyMap(), ByteArray(0))).status)
    }

    @Test
    fun lowerCaseMethodStillResolvesViaFallback() = runBlocking {
        val d = dispatcher(HttpMethod.GET)
        // Non-standard casing must still map through the uppercase() fallback.
        assertEquals(200, d.dispatch(BufferedHttpRequest("get", "/x", "", emptyMap(), ByteArray(0))).status)
    }

    @Test
    fun unknownMethodIs405() = runBlocking {
        val d = dispatcher(HttpMethod.GET)
        assertEquals(405, d.dispatch(BufferedHttpRequest("BREW", "/x", "", emptyMap(), ByteArray(0))).status)
    }
}
