package neton.http

import kotlinx.coroutines.runBlocking
import neton.core.annotations.RateLimitScope
import neton.core.annotations.RateLimitStrategy
import neton.core.http.HttpContext
import neton.core.http.HttpMethod
import neton.core.http.HttpRequest
import neton.core.http.HttpResponse
import neton.core.http.HttpSession
import neton.core.http.HttpStatus
import neton.core.interfaces.Identity
import neton.core.interfaces.RateLimitConfig
import neton.core.interfaces.RateLimitGate
import neton.core.interfaces.RouteDefinition
import neton.core.interfaces.RouteHandler
import neton.core.interfaces.SecurityAttributes
import neton.core.mock.MockIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `@RateLimit` 执行契约。
 *
 * 回归背景：限流唯一的执行点曾经在 `DefaultRequestEngine.processRequest` 里，而真实分发由
 * HTTP 适配器直接调用 `RouteDefinition.handler`，从不经过那条链——于是注解、KSP 元数据、
 * 限流器全都在，却**一次限流都没执行过**，登录爆破与短信轰炸防护实际为零。
 *
 * 所以这里锁住的核心事实是：**带 rateLimit 的路由必须真的问过 gate，且被拒时不执行 handler**。
 */
class RateLimitPreHandleContractTest {

    private class RecordingGate(private val allow: Boolean) : RateLimitGate {
        var calls = 0
        var lastRouteId: String? = null
        var lastConfig: RateLimitConfig? = null
        var lastIdentity: Identity? = null

        override suspend fun allow(
            context: HttpContext,
            routeId: String,
            config: RateLimitConfig,
            identity: Identity?,
        ): Boolean {
            calls++
            lastRouteId = routeId
            lastConfig = config
            lastIdentity = identity
            return allow
        }
    }

    private fun testCtx(attributes: MutableMap<String, Any> = mutableMapOf()) = object : HttpContext {
        override val traceId = "test"
        override val attributes = attributes
        override val request = object : HttpRequest {
            override val method = HttpMethod.GET
            override val path = "/"
            override val url = "/"
            override val version = "HTTP/1.1"
            override val cookies = emptyMap<String, neton.core.http.Cookie>()
            override val remoteAddress = "127.0.0.1"
            override val isSecure = false
            override val headers = object : neton.core.http.Headers {
                override fun get(name: String): String? = null
                override fun getAll(name: String): List<String> = emptyList()
                override fun contains(name: String) = false
                override fun names(): Set<String> = emptySet()
                override fun toMap(): Map<String, List<String>> = emptyMap()
            }
            override val queryParams = object : neton.core.http.Parameters {
                override fun get(name: String): String? = null
                override fun getAll(name: String): List<String> = emptyList()
                override fun contains(name: String) = false
                override fun names(): Set<String> = emptySet()
                override fun toMap(): Map<String, List<String>> = emptyMap()
            }
            override val pathParams = queryParams
            override suspend fun body(): ByteArray = ByteArray(0)
            override suspend fun text(): String = ""
            override suspend fun json(): Any = emptyMap<String, Any>()
            override suspend fun form(): neton.core.http.Parameters = queryParams
        }
        override val response = object : HttpResponse {
            override var status = HttpStatus.OK
            override val isCommitted = false
            override fun cookie(cookie: neton.core.http.Cookie) {}
            override suspend fun write(data: ByteArray) {}
            override val headers = object : neton.core.http.MutableHeaders {
                override fun get(name: String): String? = null
                override fun getAll(name: String): List<String> = emptyList()
                override fun contains(name: String) = false
                override fun names(): Set<String> = emptySet()
                override fun toMap(): Map<String, List<String>> = emptyMap()
                override fun set(name: String, value: String) {}
                override fun add(name: String, value: String) {}
                override fun remove(name: String) {}
                override fun clear() {}
            }
        }
        override val session = object : HttpSession {
            override fun getAttribute(name: String): Any? = null
            override fun setAttribute(name: String, value: Any?) {}
            override fun removeAttribute(name: String): Any? = null
            override fun getAttributeNames(): Set<String> = emptySet()
            override fun invalidate() {}
            override fun touch() {}
            override val id = "test"
            override val creationTime = 0L
            override val lastAccessTime = 0L
            override var maxInactiveInterval = 1800
            override val isNew = true
            override val isValid = true
        }
    }

    private fun route(
        rateLimit: RateLimitConfig?,
        controllerClass: String? = "controller.app.auth.AuthController",
        methodName: String = "login",
    ) = RouteDefinition(
        pattern = "/auth/login",
        method = HttpMethod.POST,
        handler = object : RouteHandler {
            override suspend fun invoke(context: HttpContext, args: neton.core.http.HandlerArgs): Any? = null
        },
        controllerClass = controllerClass,
        methodName = methodName,
        rateLimit = rateLimit,
    )

    /** 与 module-member 登录端点同款配置：5 分钟 10 次、按 IP。 */
    private val loginLimit = RateLimitConfig(
        windowSeconds = 300,
        maxRequests = 10,
        scope = RateLimitScope.IP,
        key = "",
        strategy = RateLimitStrategy.FIXED_WINDOW,
        message = "Login attempts exceeded",
    )

    @Test
    fun annotatedRouteConsultsTheGate() = runBlocking {
        // 这就是曾经断掉的那一环：以前 gate 一次都不会被调用
        val gate = RecordingGate(allow = true)
        val allowed = runRateLimitPreHandle(route(loginLimit), testCtx(), gate)

        assertTrue(allowed)
        assertEquals(1, gate.calls, "带 @RateLimit 的路由必须问过 gate")
        assertEquals(loginLimit, gate.lastConfig)
    }

    @Test
    fun deniedRequestReportsNotAllowedSoHandlerIsSkipped() = runBlocking {
        val gate = RecordingGate(allow = false)
        val allowed = runRateLimitPreHandle(route(loginLimit), testCtx(), gate)

        assertFalse(allowed, "gate 拒绝时必须返回 false，调用方据此跳过业务 handler")
        assertEquals(1, gate.calls)
    }

    @Test
    fun routeWithoutAnnotationSkipsTheGateEntirely() = runBlocking {
        val gate = RecordingGate(allow = true)
        val allowed = runRateLimitPreHandle(route(rateLimit = null), testCtx(), gate)

        assertTrue(allowed)
        assertEquals(0, gate.calls, "没标注解的路由不应产生限流开销")
    }

    @Test
    fun missingGateAllowsRatherThanFailing() = runBlocking {
        // 没装 routing { } 时没有 gate：放行，而不是把请求打死
        assertTrue(runRateLimitPreHandle(route(loginLimit), testCtx(), gate = null))
    }

    @Test
    fun identityFromSecurityPipelineIsPassedThrough() = runBlocking {
        // USER scope 的配额按身份分组，identity 必须从鉴权阶段写入的 attributes 里取到
        val identity = MockIdentity(id = "u-42")
        val gate = RecordingGate(allow = true)
        val attributes = mutableMapOf<String, Any>(SecurityAttributes.IDENTITY to identity)

        runRateLimitPreHandle(route(loginLimit), testCtx(attributes), gate)

        assertEquals(identity, gate.lastIdentity)
    }

    @Test
    fun anonymousRequestPassesNullIdentity() = runBlocking {
        val gate = RecordingGate(allow = true)
        runRateLimitPreHandle(route(loginLimit), testCtx(), gate)
        assertNull(gate.lastIdentity)
    }

    @Test
    fun routeIdGroupsByControllerMethod() {
        assertEquals(
            "controller.app.auth.AuthController.login",
            rateLimitRouteId(route(loginLimit)),
        )
    }

    @Test
    fun routeIdFallsBackToMethodAndPatternForDslRoutes() {
        // DSL 注册的路由没有 controllerClass
        assertEquals(
            "POST:/auth/login",
            rateLimitRouteId(route(loginLimit, controllerClass = null)),
        )
    }
}
