package neton.http.hyper4k

import hyper4k.Hyper4kRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import neton.core.annotations.RateLimitScope
import neton.core.annotations.RateLimitStrategy
import neton.core.Neton
import neton.core.component.CorsConfig
import neton.core.component.NetonContext
import neton.core.http.HttpContext
import neton.core.http.HttpMethod
import neton.core.http.NetonErrorCode
import neton.core.http.adapter.HttpCapability
import neton.core.interfaces.ConfiguredRouteGroups
import neton.core.interfaces.RateLimitConfig
import neton.core.interfaces.RateLimitGate
import neton.core.interfaces.RequestEngine
import neton.core.interfaces.RouteDefinition
import neton.core.interfaces.RouteGroupMounts
import neton.core.interfaces.RouteHandler
import neton.http.HttpAdapterFactory
import neton.http.HttpServerConfig
import neton.http.http
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class Hyper4kHttpAdapterTest {
    @Test
    fun constructorCanBePassedToHttpDsl() {
        Neton.LaunchBuilder().http(::Hyper4kHttpAdapter)
    }

    @Test
    fun createsConfiguredAdapter() {
        val factory: HttpAdapterFactory = ::Hyper4kHttpAdapter
        val adapter = factory(
            HttpServerConfig(port = 0),
            neton.core.http.DefaultParamConverterRegistry(),
        )

        assertIs<Hyper4kHttpAdapter>(adapter)
        assertEquals(
            setOf(
                HttpCapability.ASYNC_HANDOFF,
                HttpCapability.STREAMING_RESPONSE,
                HttpCapability.HTTP_2,
            ),
            adapter.capabilities,
        )
    }

    @Test
    fun dispatchesMountedRoute() = runBlocking {
        val route = RouteDefinition(
            pattern = "/users/{id}",
            method = HttpMethod.GET,
            routeGroup = "admin",
            allowAnonymous = true,
            handler = object : RouteHandler {
                override suspend fun invoke(context: HttpContext, args: neton.core.http.HandlerArgs): Any {
                    assertEquals("42", args.first("id"))
                    assertEquals(listOf("a", "b"), args.all("tag"))
                    return mapOf("id" to 42)
                }
            },
        )
        val engine = TestRequestEngine(listOf(route))
        val context = NetonContext(emptyArray()).apply {
            bind(RequestEngine::class, engine)
            bind(ConfiguredRouteGroups(setOf("admin")))
            bind(RouteGroupMounts(mapOf("admin" to "/control")))
        }
        val adapter = newAdapter(HttpServerConfig(port = 0))
        adapter.bindContext(context)

        val response = adapter.dispatch(
            Hyper4kRequest(
                method = "GET",
                path = "/control/users/42",
                query = "tag=a&tag=b",
                rawHeaders = "X-Request-Id: test-request\n",
                body = ByteArray(0),
            ),
        )

        assertEquals(200, response.status)
        val envelope = Json.parseToJsonElement(response.body.decodeToString()).jsonObject
        assertEquals(0, envelope.getValue("code").jsonPrimitive.content.toInt())
        assertEquals(42, envelope.getValue("data").jsonObject.getValue("id").jsonPrimitive.content.toInt())
    }

    @Test
    fun handlesCorsPreflight() = runBlocking {
        val cors = CorsConfig().apply {
            allowedOrigins = listOf("https://admin.example.com")
            allowedMethods = listOf("GET", "POST")
            allowedHeaders = listOf("Authorization", "Content-Type")
            allowCredentials = true
        }
        val adapter = newAdapter(HttpServerConfig(port = 0, corsConfig = cors))

        val response = adapter.dispatch(
            Hyper4kRequest(
                method = "OPTIONS",
                path = "/admin/users",
                query = "",
                rawHeaders = "Origin: https://admin.example.com\nAccess-Control-Request-Method: GET\n",
                body = ByteArray(0),
            ),
        )

        assertEquals(204, response.status)
        assertEquals(listOf("https://admin.example.com"), response.headers["Access-Control-Allow-Origin"])
        assertEquals(listOf("true"), response.headers["Access-Control-Allow-Credentials"])
    }

    @Test
    fun enforcesRateLimitBeforeHandler() = runBlocking {
        var handlerInvoked = false
        val route = RouteDefinition(
            pattern = "/limited",
            method = HttpMethod.GET,
            allowAnonymous = true,
            rateLimit = RateLimitConfig(
                windowSeconds = 60,
                maxRequests = 1,
                scope = RateLimitScope.GLOBAL,
                key = "",
                strategy = RateLimitStrategy.FIXED_WINDOW,
                message = "Too many requests",
            ),
            handler = object : RouteHandler {
                override suspend fun invoke(context: HttpContext, args: neton.core.http.HandlerArgs): Any {
                    handlerInvoked = true
                    return "unexpected"
                }
            },
        )
        val context = NetonContext(emptyArray()).apply {
            bind(RequestEngine::class, TestRequestEngine(listOf(route)))
            bind(RateLimitGate::class, object : RateLimitGate {
                override suspend fun allow(
                    context: HttpContext,
                    routeId: String,
                    config: RateLimitConfig,
                    identity: neton.core.interfaces.Identity?,
                ): Boolean {
                    context.response.status = neton.core.http.HttpStatus.TOO_MANY_REQUESTS
                    context.response.write("limited".encodeToByteArray())
                    return false
                }
            })
        }
        val adapter = newAdapter(HttpServerConfig(port = 0))
        adapter.bindContext(context)

        val response = adapter.dispatch(
            Hyper4kRequest("GET", "/limited", "", "", ByteArray(0)),
        )

        assertEquals(429, response.status)
        assertEquals(false, handlerInvoked)
    }

    @Test
    fun keepsNetonEnvelopeForTransportTimeout() {
        val response = newAdapter(HttpServerConfig(port = 0)).transportFailureResponse(504, "Gateway Timeout")
        val envelope = Json.parseToJsonElement(response.body.decodeToString()).jsonObject

        assertEquals(504, response.status)
        assertEquals(NetonErrorCode.TIMEOUT, envelope.getValue("code").jsonPrimitive.content.toInt())
    }
}

private fun newAdapter(config: HttpServerConfig): Hyper4kHttpAdapter =
    Hyper4kHttpAdapter(config, neton.core.http.DefaultParamConverterRegistry())

private class TestRequestEngine(
    private val routes: List<RouteDefinition>,
) : RequestEngine {
    override fun registerRoute(route: RouteDefinition) = Unit
    override fun getRoutes(): List<RouteDefinition> = routes
}
