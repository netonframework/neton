package neton.http.adapter

import kotlinx.coroutines.runBlocking
import neton.core.component.NetonContext
import neton.core.http.*
import neton.core.http.adapter.HttpServerConfig
import neton.core.interfaces.*
import kotlin.test.*

class AdmissionContractTest {
    @Test fun rejectsBeforeAuthenticationAndHandlerEvenWithToken() = runBlocking {
        var invoked = false
        val route = RouteDefinition("/protected", HttpMethod.GET, requireAuth = true,
            handler = object : RouteHandler {
                override suspend fun invoke(context: HttpContext, args: HandlerArgs): Any {
                    invoked = true
                    return Unit
                }
            })
        val ctx = NetonContext(emptyArray()).apply {
            bind(RequestEngine::class, object : RequestEngine {
                override fun registerRoute(route: RouteDefinition) = Unit
                override fun getRoutes() = listOf(route)
            })
            bind(RequestAdmissionGate::class, RequestAdmissionGate { context, _ ->
                context.response.status = HttpStatus.FORBIDDEN
                context.response.json(mapOf("code" to 403))
                false
            })
        }
        val dispatcher = BufferedHttpDispatcher(HttpServerConfig(port = 0)).also { it.bind(ctx) }
        val response = dispatcher.dispatch(BufferedHttpRequest("GET", "/protected", "",
            mapOf("Authorization" to listOf("Bearer invalid")), ByteArray(0)))
        assertEquals(403, response.status) // Authentication without an authenticator would fail differently.
        assertFalse(invoked)
    }

    @Test fun addressIsResolvedOnceForAdmissionAndHandler() = runBlocking {
        var resolved = 0
        val route = RouteDefinition("/public", HttpMethod.GET, allowAnonymous = true,
            handler = object : RouteHandler {
                override suspend fun invoke(context: HttpContext, args: HandlerArgs): Any {
                    assertEquals("192.0.2.1", context.request.clientAddress)
                    return Unit
                }
            })
        val ctx = NetonContext(emptyArray()).apply {
            bind(RequestEngine::class, object : RequestEngine {
                override fun registerRoute(route: RouteDefinition) = Unit
                override fun getRoutes() = listOf(route)
            })
            bind(ClientAddressResolver::class, ClientAddressResolver { resolved++; "192.0.2.1" })
            bind(RequestAdmissionGate::class, RequestAdmissionGate { context, _ ->
                assertEquals("192.0.2.1", context.request.clientAddress)
                true
            })
        }
        val dispatcher = BufferedHttpDispatcher(HttpServerConfig(port = 0)).also { it.bind(ctx) }
        assertEquals(200, dispatcher.dispatch(BufferedHttpRequest("GET", "/public", "", emptyMap(), ByteArray(0))).status)
        assertEquals(1, resolved)
    }
}
