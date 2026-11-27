package neton.http

import neton.core.component.CorsConfig
import neton.core.component.NetonContext
import neton.core.http.adapter.HttpServerConfig
import neton.core.interfaces.*
import neton.http.adapter.*
import kotlin.test.*

class CorsSnapshotTest {
    private val component = HttpComponent()
    private fun headers(config: CorsConfig, origin: String): Map<String, List<String>> {
        val ctx = NetonContext(emptyArray()).apply {
            bind(CorsConfig::class, config)
            bind(RequestEngine::class, object : RequestEngine {
                override fun registerRoute(route: RouteDefinition) = Unit
                override fun getRoutes() = emptyList<RouteDefinition>()
            })
        }
        val d = BufferedHttpDispatcher(HttpServerConfig(port = 0))
        d.bind(ctx)
        return d.corsHeaders(BufferedHttpRequest("GET", "/", "", mapOf("Origin" to listOf(origin)), byteArrayOf()))
    }

    @Test fun credentialsRejectWildcard() {
        val config = CorsConfig().apply { allowCredentials = true }
        assertFailsWith<IllegalArgumentException> { component.effectiveCors(null, config) }
        config.allowedOrigins = listOf("https://a.test", "*")
        assertFailsWith<IllegalArgumentException> { component.effectiveCors(null, config) }
        assertFailsWith<IllegalArgumentException> {
            component.effectiveCors(mapOf("cors" to mapOf("allowCredentials" to true)), CorsConfig())
        }
        val public = component.effectiveCors(mapOf("cors" to mapOf("allowCredentials" to false)), config)!!
        val actual = headers(public, "https://b.test")
        assertEquals(listOf("*"), actual["Access-Control-Allow-Origin"])
        assertNull(actual["Access-Control-Allow-Credentials"])
    }

    @Test fun effectiveOriginsDoNotFollowDslMutations() {
        val origins = mutableListOf("https://a.test")
        val config = CorsConfig().apply { allowedOrigins = origins; allowCredentials = true }
        val snapshot = component.effectiveCors(null, config)!!
        origins.add("*")
        config.allowCredentials = false
        val actual = headers(snapshot, "https://a.test")
        assertEquals(listOf("https://a.test"), actual["Access-Control-Allow-Origin"])
        assertEquals(listOf("true"), actual["Access-Control-Allow-Credentials"])
        assertNull(headers(snapshot, "https://b.test")["Access-Control-Allow-Origin"])
    }
}
