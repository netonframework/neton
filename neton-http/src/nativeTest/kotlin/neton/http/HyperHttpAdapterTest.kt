package neton.http

import hyper4k.Hyper4kRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import neton.core.component.NetonContext
import neton.core.component.CorsConfig
import neton.core.component.HttpEngine
import neton.core.http.HttpContext
import neton.core.http.HttpMethod
import neton.core.interfaces.ConfiguredRouteGroups
import neton.core.interfaces.RequestEngine
import neton.core.interfaces.RouteDefinition
import neton.core.interfaces.RouteGroupMounts
import neton.core.interfaces.RouteHandler
import neton.core.security.AuthenticationContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class HyperHttpAdapterTest {
    @Test
    fun createsConfiguredAdapter() {
        val adapter = createHttpAdapter(
            HttpEngine.HYPER4K,
            HttpServerConfig(port = 0),
            neton.core.http.DefaultParamConverterRegistry(),
        )

        assertIs<HyperHttpAdapter>(adapter)
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
        val adapter = HyperHttpAdapter(HttpServerConfig(port = 0))
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
        val adapter = HyperHttpAdapter(HttpServerConfig(port = 0, corsConfig = cors))

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
        assertEquals("https://admin.example.com", response.headers["Access-Control-Allow-Origin"])
        assertEquals("true", response.headers["Access-Control-Allow-Credentials"])
    }
}

private class TestRequestEngine(
    private val routes: List<RouteDefinition>,
) : RequestEngine {
    override suspend fun processRequest(context: HttpContext): Any? = null
    override fun registerRoute(route: RouteDefinition) = Unit
    override fun getRoutes(): List<RouteDefinition> = routes
    override fun setAuthenticationContext(authContext: AuthenticationContext) = Unit
}
