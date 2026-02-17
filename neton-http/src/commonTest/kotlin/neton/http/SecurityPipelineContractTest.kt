package neton.http

import kotlinx.coroutines.runBlocking
import neton.core.http.HttpContext
import neton.core.http.HttpException
import neton.core.http.HttpMethod
import neton.core.http.HttpRequest
import neton.core.http.HttpResponse
import neton.core.http.HttpSession
import neton.core.http.HttpStatus
import neton.core.interfaces.Identity
import neton.core.interfaces.RequestContext
import neton.core.interfaces.RouteDefinition
import neton.core.interfaces.RouteGroupSecurityConfig
import neton.core.interfaces.RouteGroupSecurityConfigs
import neton.core.interfaces.SecurityAttributes
import neton.core.interfaces.SecurityConfiguration
import neton.core.mock.MockAuthenticationContext
import neton.core.mock.MockIdentity
import neton.core.mock.MockAuthenticator
import neton.core.mock.MockDefaultGuard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 安全管道契约测试（v1.2）
 * 锁住 Mode A/B、Guard 选择、@Permission、PermissionEvaluator、路由组白名单。
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

    private fun reqCtx(path: String = "/", method: String = "GET", routeGroup: String? = null) =
        object : RequestContext {
            override val path = path
            override val method = method
            override val headers = emptyMap<String, String>()
            override val routeGroup: String? = routeGroup
        }

    private fun route(
        allowAnonymous: Boolean = false,
        requireAuth: Boolean = false,
        permission: String? = null,
        pattern: String = "/test"
    ) = RouteDefinition(
        pattern = pattern,
        method = HttpMethod.GET,
        handler = object : neton.core.interfaces.RouteHandler {
            override suspend fun invoke(context: HttpContext, args: neton.core.http.HandlerArgs) = "ok"
        },
        controllerClass = "TestController",
        methodName = "test",
        allowAnonymous = allowAnonymous,
        requireAuth = requireAuth,
        permission = permission
    )

    private fun secConfig(
        identity: MockIdentity? = null,
        hasGuard: Boolean = true
    ) = SecurityConfiguration(
        isEnabled = true,
        authenticatorCount = if (identity != null) 1 else 0,
        guardCount = if (hasGuard) 1 else 0,
        authenticationContext = MockAuthenticationContext(),
        defaultAuthenticator = if (identity != null) MockAuthenticator(identity) else null,
        defaultGuard = if (hasGuard) MockDefaultGuard() else null
    )

    // --- Test 1: Mode A 默认开放 ---
    @Test
    fun modeA_plainRoute_noSecurity_returns200() = runBlocking {
        val ctx = testCtx()
        runSecurityPreHandle(route(allowAnonymous = false, requireAuth = false), ctx, reqCtx(), null)
        assertNull(ctx.getAttribute(SecurityAttributes.IDENTITY))
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

    // --- Test 3: Mode B + RequireAuth + MockAuthenticator → 200, identity set ---
    @Test
    fun modeB_requireAuth_withMockAuthenticator_setsIdentity() = runBlocking {
        val attrs = mutableMapOf<String, Any>()
        val ctx = testCtx(attrs)
        val mockId = MockIdentity("user-1", setOf("user"))
        val config = secConfig(mockId)
        runSecurityPreHandle(route(requireAuth = true), ctx, reqCtx(), config)
        val identity = ctx.getAttribute(SecurityAttributes.IDENTITY) as? Identity
        assertEquals("user-1", identity?.id)
    }

    // --- Test 4: AllowAnonymous 永远放行，identity 为 null ---
    @Test
    fun allowAnonymous_alwaysPasses_identityNull() = runBlocking {
        val attrs = mutableMapOf<String, Any>()
        val ctx = testCtx(attrs)
        val config = secConfig(MockIdentity("x", emptySet()))
        runSecurityPreHandle(route(allowAnonymous = true), ctx, reqCtx(), config)
        assertNull(ctx.getAttribute(SecurityAttributes.IDENTITY))
    }

    // --- Test 5: 已安装 Security 但未注册 Authenticator，requireAuth → 500 ---
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

    // --- Test 6: @Permission 有权限 → 放行 ---
    @Test
    fun permission_allowed_passes() = runBlocking {
        val attrs = mutableMapOf<String, Any>()
        val ctx = testCtx(attrs)
        val mockId = MockIdentity("admin", setOf("admin"), setOf("system:user:edit"))
        val config = secConfig(mockId)
        runSecurityPreHandle(route(requireAuth = true, permission = "system:user:edit"), ctx, reqCtx(), config)
        val identity = ctx.getAttribute(SecurityAttributes.IDENTITY) as? Identity
        assertEquals("admin", identity?.id)
    }

    // --- Test 7: @Permission 无权限 → 403 ---
    @Test
    fun permission_denied_throws403() = runBlocking {
        val ctx = testCtx()
        val mockId = MockIdentity("user-1", setOf("user"), setOf("read"))
        val config = secConfig(mockId)
        val ex = kotlin.runCatching {
            runSecurityPreHandle(route(requireAuth = true, permission = "system:user:edit"), ctx, reqCtx(), config)
        }.exceptionOrNull() as? HttpException
            ?: error("Expected HttpException")
        assertEquals(HttpStatus.FORBIDDEN, ex.status)
        assertTrue(ex.message?.contains("system:user:edit") == true)
    }

    // --- Test 8: PermissionEvaluator 自定义逻辑（superadmin 绕过） ---
    @Test
    fun permissionEvaluator_superadmin_bypasses() = runBlocking {
        val attrs = mutableMapOf<String, Any>()
        val ctx = testCtx(attrs)
        val mockId = MockIdentity("superadmin", setOf("superadmin"), emptySet())
        val config = SecurityConfiguration(
            isEnabled = true,
            authenticatorCount = 1,
            guardCount = 1,
            authenticationContext = MockAuthenticationContext(),
            defaultAuthenticator = MockAuthenticator(mockId),
            defaultGuard = MockDefaultGuard(),
            permissionEvaluator = neton.core.interfaces.PermissionEvaluator { identity, _, _ ->
                identity.hasRole("superadmin") || identity.hasPermission("system:user:edit")
            }
        )
        runSecurityPreHandle(route(requireAuth = true, permission = "system:user:edit"), ctx, reqCtx(), config)
        val identity = ctx.getAttribute(SecurityAttributes.IDENTITY) as? Identity
        assertEquals("superadmin", identity?.id)
    }

    // --- Test 9: 路由组白名单放行 ---
    @Test
    fun routeGroupWhitelist_allowsAnonymous() = runBlocking {
        val ctx = testCtx()
        val groupConfigs = RouteGroupSecurityConfigs(
            mapOf(
                "admin" to RouteGroupSecurityConfig(requireAuth = true, allowAnonymous = setOf("/login"))
            )
        )
        val config = secConfig(MockIdentity("x", emptySet()))
        runSecurityPreHandle(
            route(pattern = "/login", requireAuth = false),
            ctx,
            reqCtx(routeGroup = "admin"),
            config,
            groupConfigs
        )
        assertNull(ctx.getAttribute(SecurityAttributes.IDENTITY))
    }

    // --- Test 10: 路由组 requireAuth 强制认证 ---
    @Test
    fun routeGroup_requireAuth_enforcesAuth() = runBlocking {
        val ctx = testCtx()
        val groupConfigs = RouteGroupSecurityConfigs(
            mapOf(
                "admin" to RouteGroupSecurityConfig(requireAuth = true, allowAnonymous = emptySet())
            )
        )
        val config = SecurityConfiguration(
            isEnabled = true,
            authenticatorCount = 0,
            guardCount = 0,
            authenticationContext = MockAuthenticationContext(),
            defaultAuthenticator = null,
            defaultGuard = null
        )
        val ex = kotlin.runCatching {
            runSecurityPreHandle(
                route(pattern = "/dashboard", requireAuth = false),
                ctx,
                reqCtx(routeGroup = "admin"),
                config,
                groupConfigs
            )
        }.exceptionOrNull() as? HttpException
            ?: error("Expected HttpException")
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, ex.status)
    }

    // --- Test 11: @Permission + 无 evaluator + 空 permissions → 403（默认行为冻结） ---
    @Test
    fun permission_noEvaluator_emptyPermissions_throws403() = runBlocking {
        val ctx = testCtx()
        val mockId = MockIdentity("user-1", setOf("user"), emptySet())
        val config = secConfig(mockId)
        val ex = kotlin.runCatching {
            runSecurityPreHandle(route(requireAuth = true, permission = "system:user:edit"), ctx, reqCtx(), config)
        }.exceptionOrNull() as? HttpException
            ?: error("Expected HttpException")
        assertEquals(HttpStatus.FORBIDDEN, ex.status)
        assertTrue(ex.message?.contains("system:user:edit") == true)
    }

    // --- Test 12: @Permission + identity == null → 401（未认证） ---
    @Test
    fun permission_noIdentity_throws401() = runBlocking {
        val ctx = testCtx()
        val config = SecurityConfiguration(
            isEnabled = true,
            authenticatorCount = 1,
            guardCount = 1,
            authenticationContext = MockAuthenticationContext(),
            defaultAuthenticator = MockAuthenticator(null),
            defaultGuard = MockDefaultGuard()
        )
        val ex = kotlin.runCatching {
            runSecurityPreHandle(route(requireAuth = false, permission = "any:perm"), ctx, reqCtx(), config)
        }.exceptionOrNull() as? HttpException
            ?: error("Expected HttpException")
        assertEquals(HttpStatus.UNAUTHORIZED, ex.status)
    }

    // ── beta1 冻结：permission implies auth ─────────────────────

    // --- Test 13: @Permission 隐含认证 — open 组下无 requireAuth 也必须 401 ---
    // 冻结规则：route.permission != null → requireAuth 强制为 true
    @Test
    fun permissionImpliesAuth_openGroup_noToken_throws401() = runBlocking {
        val ctx = testCtx()
        val groupConfigs = RouteGroupSecurityConfigs(
            mapOf(
                "admin" to RouteGroupSecurityConfig(requireAuth = false, allowAnonymous = emptySet())
            )
        )
        val config = SecurityConfiguration(
            isEnabled = true,
            authenticatorCount = 1,
            guardCount = 1,
            authenticationContext = MockAuthenticationContext(),
            defaultAuthenticator = MockAuthenticator(null),  // 无 token → identity = null
            defaultGuard = MockDefaultGuard()
        )
        val ex = kotlin.runCatching {
            runSecurityPreHandle(
                route(requireAuth = false, permission = "system:user:page"),
                ctx,
                reqCtx(routeGroup = "admin"),
                config,
                groupConfigs
            )
        }.exceptionOrNull() as? HttpException
            ?: error("Expected HttpException for permission-implies-auth")
        assertEquals(HttpStatus.UNAUTHORIZED, ex.status)
    }

    // --- Test 14: @Permission + 有效 identity → 放行（open 组下也行） ---
    @Test
    fun permissionImpliesAuth_openGroup_withToken_passes() = runBlocking {
        val attrs = mutableMapOf<String, Any>()
        val ctx = testCtx(attrs)
        val groupConfigs = RouteGroupSecurityConfigs(
            mapOf(
                "admin" to RouteGroupSecurityConfig(requireAuth = false, allowAnonymous = emptySet())
            )
        )
        val mockId = MockIdentity("admin", setOf("admin"), setOf("system:user:page"))
        val config = SecurityConfiguration(
            isEnabled = true,
            authenticatorCount = 1,
            guardCount = 1,
            authenticationContext = MockAuthenticationContext(),
            defaultAuthenticator = MockAuthenticator(mockId),
            defaultGuard = MockDefaultGuard()
        )
        runSecurityPreHandle(
            route(requireAuth = false, permission = "system:user:page"),
            ctx,
            reqCtx(routeGroup = "admin"),
            config,
            groupConfigs
        )
        val identity = ctx.getAttribute(SecurityAttributes.IDENTITY) as? Identity
        assertEquals("admin", identity?.id)
    }

    // --- Test 15: @Permission 无安全配置 → 500（fail-fast） ---
    // 冻结规则：permission 隐含 requireAuth，安全未配置时必须 500 而非静默放行
    @Test
    fun permissionImpliesAuth_noSecurity_throws500() = runBlocking {
        val ctx = testCtx()
        val ex = kotlin.runCatching {
            runSecurityPreHandle(
                route(requireAuth = false, permission = "system:user:page"),
                ctx,
                reqCtx(),
                null
            )
        }.exceptionOrNull() as? HttpException
            ?: error("Expected HttpException for permission-implies-auth fail-fast")
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, ex.status)
    }
}
