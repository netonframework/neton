package neton.http.adapter

import neton.core.component.NetonContext
import neton.core.http.HandlerArgs
import neton.core.http.HttpContext
import neton.core.http.HttpMethod
import neton.core.http.adapter.HttpServerConfig
import neton.core.interfaces.RequestEngine
import neton.core.interfaces.RouteDefinition
import neton.core.interfaces.RouteHandler
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The full request header map is expensive; an anonymous handler that only reads
 * query params (the baseline/arithmetic shape) must never force it to be built.
 * This pins that: with no security installed and a route needing no auth, the
 * headersProvider is called zero times end to end.
 */
class HeaderLazinessTest {

    private val providerCalls = intArrayOf(0)

    private fun request(query: String): BufferedHttpRequest {
        providerCalls[0] = 0
        return BufferedHttpRequest(
            method = "GET",
            path = "/e",
            query = query,
            body = ByteArray(0),
            remoteAddress = "",
            singleHeader = { _ -> null },
            headersProvider = { providerCalls[0]++; emptyMap() },
        )
    }

    private fun dispatcher(handler: RouteHandler): BufferedHttpDispatcher {
        val ctx = NetonContext(emptyArray()).apply {
            bind(RequestEngine::class, object : RequestEngine {
                override fun registerRoute(route: RouteDefinition) = Unit
                override fun getRoutes() = listOf(
                    RouteDefinition("/e", HttpMethod.GET, allowAnonymous = true, handler = handler),
                )
            })
        }
        return BufferedHttpDispatcher(HttpServerConfig(port = 0)).also { it.bind(ctx) }
    }

    @Test
    fun anonymousQueryOnlyHandlerNeverBuildsHeaderMap() = runBlocking {
        val disp = dispatcher(object : RouteHandler {
            override suspend fun invoke(context: HttpContext, args: HandlerArgs): Any {
                val sum = (context.request.queryParam("a")?.toIntOrNull() ?: 0) +
                    (context.request.queryParam("b")?.toIntOrNull() ?: 0)
                context.response.text(sum.toString())
                return Unit
            }
        })
        val resp = disp.dispatch(request("a=3&b=4"))
        assertEquals("7", resp.body.decodeToString())
        assertEquals(0, providerCalls[0], "the full header map must not be built for an anonymous query-only request")
    }
}
