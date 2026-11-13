package neton.http.adapter

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import neton.core.component.NetonContext
import neton.core.http.*
import neton.core.http.adapter.HttpServerConfig
import neton.core.interfaces.*
import neton.core.annotations.RateLimitScope
import neton.core.annotations.RateLimitStrategy
import neton.core.security.DisabledAuthenticationContext
import kotlin.test.*

class RouteLifecycleTest {
    private class Fixture(
        permission: String? = null, rateLimit: RateLimitConfig? = null,
        handler: suspend (HttpContext) -> Any? = { Unit },
    ) {
        val route = RouteDefinition("/item/{id}", HttpMethod.GET, routeGroup = "admin", permission = permission,
            rateLimit = rateLimit,
            handler = object : RouteHandler {
                override suspend fun invoke(context: HttpContext, args: HandlerArgs) = handler(context)
            })
        val ctx = NetonContext(emptyArray()).apply {
            bind(RequestEngine::class, object : RequestEngine {
                override fun registerRoute(route: RouteDefinition) = Unit
                override fun getRoutes() = listOf(this@Fixture.route)
            })
        }
        val completions = mutableListOf<RouteCompletion>()
        val lifecycle = ctx.routeLifecycle().apply { observe("test", { completions.add(it) }, order = 10) }
        val dispatcher = BufferedHttpDispatcher(HttpServerConfig(port = 0))
        suspend fun dispatch(path: String = "/admin/item/123"): BufferedHttpResponse {
            ctx.freeze()
            dispatcher.bind(ctx)
            return dispatcher.dispatch(BufferedHttpRequest("GET", path, "token=private",
                mapOf("X-Forwarded-For" to listOf("spoofed"), "X-Request-Id" to listOf("untrusted")), ByteArray(0),
                remoteAddress = "192.0.2.1"))
        }
    }

    @Test fun aroundIsOrderedAndCompletionIsReadOnly() = runBlocking<Unit> {
        val order = mutableListOf<String>()
        var id: Any? = null
        val f = Fixture { id = it.getAttribute(RouteLifecycleAttributes.INVOCATION_ID); order.add("handler"); "answer" }
        f.lifecycle.intercept("b", { _, next -> order.add("b+"); next().also { order.add("b-") } })
        f.lifecycle.intercept("a", { _, next -> order.add("a+"); next().also { order.add("a-") } },
            RouteSelector(group = "admin", method = HttpMethod.GET, pattern = "/admin/item/{id}"))
        f.lifecycle.intercept("skip", { _, _ -> error("wrong selector") }, RouteSelector(group = "app"))
        f.lifecycle.observe("broken", { error("observer failure") })
        assertFailsWith<IllegalArgumentException> { f.lifecycle.observe("a", {}) }
        assertEquals(200, f.dispatch().status)
        assertEquals(listOf("a+", "b+", "handler", "b-", "a-"), order)
        val result = f.completions.single()
        assertEquals("/admin/item/{id}", result.endpoint.pattern)
        assertEquals("192.0.2.1", result.clientAddress)
        assertEquals(id, result.invocationId)
        assertNotEquals("untrusted", result.invocationId)
        assertTrue(result.handlerEntered)
        assertFailsWith<IllegalStateException> { f.lifecycle.observe("late", {}) }
    }

    @Test fun shortCircuitAndDuplicateNextDoNotReexecuteHandler() = runBlocking {
        val short = Fixture { error("must not run") }
        short.lifecycle.intercept("short", { _, _ -> "cached" })
        assertEquals(200, short.dispatch().status)
        assertFalse(short.completions.single().handlerEntered)

        var calls = 0
        val repeated = Fixture { calls++; Unit }
        repeated.lifecycle.intercept("duplicate", { _, next -> next(); next() })
        assertEquals(500, repeated.dispatch().status)
        assertEquals(1, calls)
        assertEquals(RouteResult.FAILED, repeated.completions.single().result)
    }

    @Test fun cancellationAndCommittedFailureAreNotSuccess() = runBlocking {
        val cancelled = Fixture { throw CancellationException("cancelled") }
        assertFailsWith<CancellationException> { cancelled.dispatch() }
        assertEquals(RouteResult.CANCELLED, cancelled.completions.single().result)
        assertNull(cancelled.completions.single().httpStatus)

        val failed = Fixture { it.response.write("started".encodeToByteArray()); error("failed after commit") }
        failed.dispatch()
        assertEquals(RouteResult.FAILED, failed.completions.single().result)
        assertTrue(failed.completions.single().handlerEntered)
    }

    @Test fun admissionAndNotFoundHaveDifferentCoverage() = runBlocking {
        val f = Fixture { error("not admitted") }
        f.ctx.bind(RequestAdmissionGate::class, RequestAdmissionGate { context, _ ->
            context.response.status = HttpStatus.FORBIDDEN
            false
        })
        f.lifecycle.intercept("must-not-run", { _, _ -> error("not admitted") })
        assertEquals(403, f.dispatch().status)
        assertEquals(RoutePhase.ADMISSION, f.completions.single().phase)
        assertEquals(RouteResult.REJECTED, f.completions.single().result)
        assertFalse(f.completions.single().handlerEntered)

        val missing = Fixture()
        assertEquals(404, missing.dispatch("/missing").status)
        assertTrue(missing.completions.isEmpty())
    }

    @Test fun securityAndRateLimitRejectBeforeAround() = runBlocking {
        val protected = Fixture(permission = "edit")
        val actor = object : Identity {
            override val id = "42"
            override val roles = emptySet<String>()
            override val permissions = emptySet<String>()
        }
        val authenticator = object : Authenticator {
            override val name = "fixture"
            override suspend fun authenticate(context: RequestContext) = actor
        }
        protected.ctx.bind(SecurityConfiguration::class, SecurityConfiguration(true, 1, 0,
            DisabledAuthenticationContext(), authenticator))
        protected.lifecycle.intercept("must-not-run", { _, _ -> error("permission denied") })
        assertEquals(403, protected.dispatch().status)
        assertEquals("42", protected.completions.single().actorId)
        assertEquals(RoutePhase.SECURITY, protected.completions.single().phase)
        assertFalse(protected.completions.single().handlerEntered)

        val limited = Fixture(rateLimit = RateLimitConfig(
            60, 1, RateLimitScope.GLOBAL, "", RateLimitStrategy.FIXED_WINDOW, "",
        ))
        limited.ctx.bind(RateLimitGate::class, object : RateLimitGate {
            override suspend fun allow(context: HttpContext, routeId: String, config: RateLimitConfig, identity: Identity?): Boolean {
                context.response.status = HttpStatus.TOO_MANY_REQUESTS
                return false
            }
        })
        limited.lifecycle.intercept("must-not-run", { _, _ -> error("rate limited") })
        assertEquals(429, limited.dispatch().status)
        assertEquals(RoutePhase.RATE_LIMIT, limited.completions.single().phase)
        assertFalse(limited.completions.single().handlerEntered)
    }
}
