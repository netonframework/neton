package neton.routing.ratelimit

import kotlinx.coroutines.runBlocking
import neton.core.annotations.RateLimitScope
import neton.core.annotations.RateLimitStrategy
import neton.core.component.NetonContext
import neton.core.http.HttpContext
import neton.core.http.HttpMethod
import neton.core.http.adapter.HttpServerConfig
import neton.core.interfaces.RateLimitConfig
import neton.core.interfaces.RateLimitGate
import neton.core.interfaces.RequestEngine
import neton.core.interfaces.RouteDefinition
import neton.core.interfaces.RouteHandler
import neton.http.adapter.BufferedHttpDispatcher
import neton.http.adapter.BufferedHttpRequest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `@RateLimit` 命中时的响应（spec ERROR_CODE_SPEC）：HTTP 429 + `Retry-After`，信封
 * `code = RATE_LIMIT_EXCEEDED(10300)`（不是 HTTP 码 429），`message` = 注解给的机器码。
 */
class RateLimitEnvelopeContractTest {

    private fun dispatcher(message: String): BufferedHttpDispatcher {
        val route = RouteDefinition(
            pattern = "/limited",
            method = HttpMethod.POST,
            allowAnonymous = true,
            rateLimit = RateLimitConfig(
                windowSeconds = 60,
                maxRequests = 1,
                scope = RateLimitScope.GLOBAL,
                key = "",
                strategy = RateLimitStrategy.FIXED_WINDOW,
                message = message,
            ),
            handler = object : RouteHandler {
                override suspend fun invoke(context: HttpContext, args: neton.core.http.HandlerArgs): Any = "ok"
            },
        )
        val context = NetonContext(emptyArray()).apply {
            bind(RequestEngine::class, object : RequestEngine {
                override fun registerRoute(route: RouteDefinition) = Unit
                override fun getRoutes(): List<RouteDefinition> = listOf(route)
            })
            bind(RateLimitGate::class, RateLimitInterceptor(FixedWindowRateLimiter(LocalRateLimitStore()), DefaultRateLimitKeyResolver()))
        }
        return BufferedHttpDispatcher(HttpServerConfig(port = 0)).also { it.bind(context) }
    }

    @Test
    fun deniedRequestGetsTheStandardEnvelope() = runBlocking {
        val d = dispatcher("LOGIN_RATE_LIMITED")
        val request = BufferedHttpRequest("POST", "/limited", "", emptyMap(), ByteArray(0))
        assertEquals(200, d.dispatch(request).status)
        val denied = d.dispatch(request)
        assertEquals(429, denied.status)
        assertEquals(listOf("60"), denied.headers["Retry-After"])
        assertEquals("""{"code":10300,"message":"LOGIN_RATE_LIMITED","data":null}""", denied.body.decodeToString())
    }
}
