package neton.http

import kotlinx.coroutines.runBlocking
import neton.core.http.HttpContext
import neton.core.http.HttpException
import neton.core.http.HttpMethod
import neton.core.http.HttpRequest
import neton.core.http.HttpResponse
import neton.core.http.HttpSession
import neton.core.http.HttpStatus
import neton.core.interfaces.Principal
import neton.core.interfaces.RequestContext
import neton.core.interfaces.RouteDefinition
import neton.core.interfaces.SecurityConfiguration
import neton.core.mock.MockAuthenticationContext
import neton.core.mock.MockPrincipal
import neton.core.mock.MockAuthenticator
import neton.core.mock.MockDefaultGuard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 安全管道契约测试（v1 冻结）
 * 锁住 Mode A/B、Guard 选择、fail-fast 语义，防止 routing/ksp 重构悄悄破坏。
 */
class SecurityPipelineContractTest {

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

    private fun reqCtx(path: String = "/", method: String = "GET") = object : RequestContext {
        override val path = path
        override val method = method
        override val headers = emptyMap<String, String>()
        override val routeGroup: String? = null
    }

    private fun route(allowAnonymous: Boolean = false, requireAuth: Boolean = false) = RouteDefinition(
        pattern = "/test",
        method = HttpMethod.GET,
        handler = object : neton.core.interfaces.RouteHandler {
            override suspend fun invoke(context: HttpContext, args: neton.core.http.HandlerArgs) = "ok"
        },
        controllerClass = "TestController",
        methodName = "test",
        allowAnonymous = allowAnonymous,
        requireAuth = requireAuth
    )

    // --- Test 1: Mode A 默认开放 ---
    @Test
    fun modeA_plainRoute_noSecurity_returns200() = runBlocking {
        val ctx = testCtx()
        runSecurityPreHandle(route(allowAnonymous = false, requireAuth = false), ctx, reqCtx(), null)
        assertNull(ctx.getAttribute("principal"))
    }

    // --- Test 2: Mode A + @RequireAuth fail-fast 500 ---
    @Test
    fun modeA_requireAuth_noSecurity_throws500() = runBlocking {
        val ctx = testCtx()
        val ex = kotlin.runCatching {
            runSecurityPreHandle(route(requireAuth = true), ctx, reqCtx(), null)
        }.exceptionOrNull() as? HttpException
            ?: error("Expected HttpException")
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, ex.status)
        assertTrue(ex.message?.contains("SecurityComponent") == true)
    }

    // --- Test 3: Mode B + RequireAuth + MockAuthenticator → 200, principal set ---
    @Test
    fun modeB_requireAuth_withMockAuthenticator_setsPrincipal() = runBlocking {
        val attrs = mutableMapOf<String, Any>()
        val ctx = testCtx(attrs)
        val mockPrincipal = MockPrincipal("user-1", listOf("user"))
        val config = SecurityConfiguration(
            isEnabled = true,
            authenticatorCount = 1,
            guardCount = 1,
            authenticationContext = MockAuthenticationContext(),
            defaultAuthenticator = MockAuthenticator(mockPrincipal),
            defaultGuard = MockDefaultGuard()
        )
        runSecurityPreHandle(route(requireAuth = true), ctx, reqCtx(), config)
        val principal = ctx.getAttribute("principal") as? Principal
        assertEquals("user-1", principal?.id)
    }

    // --- Test 4: AllowAnonymous 永远放行，principal 为 null ---
    @Test
    fun allowAnonymous_alwaysPasses_principalNull() = runBlocking {
        val attrs = mutableMapOf<String, Any>()
        val ctx = testCtx(attrs)
        val config = SecurityConfiguration(
            isEnabled = true,
            authenticatorCount = 1,
            guardCount = 1,
            authenticationContext = MockAuthenticationContext(),
            defaultAuthenticator = MockAuthenticator(MockPrincipal("x", listOf())),
            defaultGuard = MockDefaultGuard()
        )
        runSecurityPreHandle(route(allowAnonymous = true), ctx, reqCtx(), config)
        assertNull(ctx.getAttribute("principal"))
    }

    // --- 额外：已安装 Security 但未注册 Authenticator，requireAuth → 500 ---
    @Test
    fun modeB_requireAuth_noAuthenticator_throws500() = runBlocking {
        val ctx = testCtx()
        val config = SecurityConfiguration(
            isEnabled = true,
            authenticatorCount = 0,
            guardCount = 0,
            authenticationContext = MockAuthenticationContext(),
            defaultAuthenticator = null,
            defaultGuard = null
        )
        val ex = kotlin.runCatching {
            runSecurityPreHandle(route(requireAuth = true), ctx, reqCtx(), config)
        }.exceptionOrNull() as? HttpException
            ?: error("Expected HttpException")
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, ex.status)
        assertTrue(ex.message?.contains("Authenticator") == true)
    }
}
