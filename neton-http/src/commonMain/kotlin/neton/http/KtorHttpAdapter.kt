package neton.http

import neton.core.component.NetonContext
import neton.core.config.ConfigLoader
import neton.core.interfaces.*
import neton.logging.CurrentLogContext
import neton.logging.LogContext
import neton.logging.LoggerFactory
import neton.logging.emptyFields
import neton.core.http.adapter.HttpAdapter
import neton.core.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.http.*
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope

/**
 * Ktor HTTP 适配器 - port/config 在构造时传入
 */
class KtorHttpAdapter(
    private val serverConfig: HttpServerConfig,
    private val paramConverterRegistry: neton.core.http.ParamConverterRegistry = neton.core.http.DefaultParamConverterRegistry()
) : HttpAdapter {

    private var requestEngine: RequestEngine? = null
    private var embeddedServer: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private var appContext: NetonContext? = null

    override fun port(): Int = serverConfig.port
    override fun adapterName(): String = "Ktor CIO"

    private fun log(): neton.logging.Logger? = appContext?.getOrNull(LoggerFactory::class)?.get("neton.http")

    override suspend fun start(ctx: NetonContext, onStarted: ((coldStartMs: Long) -> Unit)?) {
        appContext = ctx
        requestEngine = ctx.getOrNull(RequestEngine::class)
        run(serverConfig.port, ctx.args, onStarted)
    }

    private fun isPortInUse(e: Throwable): Boolean {
        var t: Throwable? = e
        while (t != null) {
            val msg = t.message ?: ""
            val name = t::class.simpleName ?: ""
            if (msg.contains("EADDRINUSE") || msg.contains("Address already in use") || name.contains("AddressAlreadyInUse")) return true
            t = t.cause
        }
        return false
    }
    
    private suspend fun run(port: Int, args: Array<String>, onStarted: ((coldStartMs: Long) -> Unit)? = null) {
        val startMs = kotlin.time.Clock.System.now().toEpochMilliseconds()
        // KTOR_LOG_LEVEL 与框架 logging.level 同步
        val appConfig = ConfigLoader.loadApplicationConfig("config", ConfigLoader.resolveEnvironment(args), args)
        @Suppress("UNCHECKED_CAST")
        val loggingSection = appConfig?.let { ConfigLoader.getConfigValue(it, "logging") as? Map<String, Any?> }
        val levelStr = (loggingSection?.get("level") as? String)?.uppercase() ?: "INFO"
        val ktorLevel = when (levelStr) {
            "TRACE", "DEBUG", "INFO", "WARN", "ERROR" -> levelStr
            else -> "INFO"
        }
        syncKtorLogLevelToConfig(ktorLevel)
        installPortInUseHandler(port)
        try {
            try {
                embeddedServer = embeddedServer(CIO, port = port, host = "0.0.0.0") {
                    // 安装内容协商插件
                    install(ContentNegotiation) {
                        json(Json {
                            prettyPrint = true
                            isLenient = true
                        })
                    }
                    // /aaa 与 /aaa/ 视为同一地址
                    install(IgnoreTrailingSlash)
                    
                    routing {
                        // === 动态注册控制器路由 ===
                        val engine = requestEngine
                        if (engine != null) {
                            val routes = engine.getRoutes()
                            val groupMounts = appContext?.getOrNull(RouteGroupMounts::class)?.groupToMount ?: emptyMap()
                            val configuredGroups = appContext?.getOrNull(ConfiguredRouteGroups::class)?.names ?: emptySet()
                            
                            val rootRoute = this
                            val routesByGroup = routes.groupBy { route ->
                                route.routeGroup ?: inferRouteGroup(route.controllerClass, configuredGroups)
                            }
                            // 无 mount 的默认组优先注册，确保 get("/") 在 route("{...}") 之前
                            val ordered = routesByGroup.entries.sortedBy { (g, _) -> if (g == null) 0 else 1 }
                            ordered.forEach { (group, groupRoutes) ->
                                val mount = if (group != null) groupMounts[group]?.takeIf { it.isNotEmpty() } ?: "" else ""
                                // 更具体的路径优先注册（否则根路径 get("/") 可能贪婪匹配）
                                val sorted = groupRoutes.sortedBy { if (it.pattern == "/" || it.pattern == "") 1 else 0 }
                                val registerBlock: io.ktor.server.routing.Route.() -> Unit = {
                                    sorted.forEach { route ->
                                        // 嵌套 route(mount) 内需用相对路径，去掉首斜杠
                                        var path = if (mount.isNotEmpty() && route.pattern.startsWith("/")) {
                                            route.pattern.removePrefix("/")
                                        } else {
                                            route.pattern
                                        }
                                        // 根路径 "" 需同时注册 get("/") 以匹配带尾斜杠的 /admin/
                                        val paths = if (mount.isNotEmpty() && path == "") listOf("", "/") else listOf(path)
                                        paths.forEach { p ->
                                            when (route.method.name) {
                                                "GET" -> get(p) { handleRoute(route, call) }
                                                "POST" -> post(p) { handleRoute(route, call) }
                                                "PUT" -> put(p) { handleRoute(route, call) }
                                                "DELETE" -> delete(p) { handleRoute(route, call) }
                                                "PATCH" -> patch(p) { handleRoute(route, call) }
                                                "HEAD" -> head(p) { handleRoute(route, call) }
                                                "OPTIONS" -> options(p) { handleRoute(route, call) }
                                                else -> { /* unsupported method */ }
                                            }
                                        }
                                    }
                                }
                                if (mount.isNotEmpty()) {
                                    val mountPath = mount.trimStart('/')
                                    // 扁平化：route("admin/index") { get { } } 避免嵌套路径匹配问题
                                    sorted.forEach { route ->
                                        val rel = if (route.pattern.startsWith("/")) route.pattern.removePrefix("/") else route.pattern
                                        val full = if (rel.isEmpty()) "/$mountPath" else "/$mountPath/$rel"
                                        listOf(full).forEach { fp ->
                                        when (route.method.name) {
                                            "GET" -> route(fp) { get { handleRoute(route, call) } }
                                            "POST" -> route(fp) { post { handleRoute(route, call) } }
                                            "PUT" -> route(fp) { put { handleRoute(route, call) } }
                                            "DELETE" -> route(fp) { delete { handleRoute(route, call) } }
                                            "PATCH" -> route(fp) { patch { handleRoute(route, call) } }
                                            "HEAD" -> route(fp) { head { handleRoute(route, call) } }
                                            "OPTIONS" -> route(fp) { options { handleRoute(route, call) } }
                                            else -> { /* unsupported method */ }
                                        }
                                        }
                                    }
                                } else {
                                    rootRoute.apply(registerBlock)
                                }
                            }
                            
                        } else {
                        }
                        
                        // 根路径 "/" 需优先于 route("{...}") 注册，否则 tailcard 会抢占
                        engine?.getRoutes()?.find { it.pattern == "/" && it.method.name == "GET" }?.let { rootGet ->
                            get("/") { handleRoute(rootGet, call) }
                        }
                        
                        // 处理未匹配的路由 - 返回 404
                        route("{...}") {
                            handle {
                                call.respond(HttpStatusCode.NotFound, "404 Not Found")
                            }
                        }
                    }
                }
                
                
                try {
                    addShutdownHandler()
                    // 启动成功后回调框架层（端口占用会 exit，不会执行到 delay 后）
                    coroutineScope {
                        launch {
                            delay(150)
                            val coldStartMs = kotlin.time.Clock.System.now().toEpochMilliseconds() - startMs
                            onStarted?.invoke(coldStartMs)
                        }
                        embeddedServer?.start(wait = true)
                    }
                } catch (e: Throwable) {
                    if (isPortInUse(e)) {
                        kotlin.io.println("Port $port is already in use. Stop the other process or use a different port.")
                        kotlin.system.exitProcess(1)
                    }
                    gracefulShutdown()
                    throw e
                }
                
            } catch (e: Throwable) {
                if (isPortInUse(e)) {
                    kotlin.io.println("Port $port is already in use. Stop the other process or use a different port.")
                    kotlin.system.exitProcess(1)
                }
                throw e
            }
        } catch (e: Throwable) {
            if (isPortInUse(e)) {
                kotlin.io.println("Port $port is already in use. Stop the other process or use a different port.")
                kotlin.system.exitProcess(1)
            }
            log()?.error("Failed to start Ktor server", mapOf("port" to port), cause = e)
            gracefulShutdown()
            throw e
        } finally {
            // 确保在任何情况下都执行清理
            // 如果是正常退出（比如 Ctrl+C），也执行优雅关闭
            if (embeddedServer != null) {
                gracefulShutdown()
            }
        }
    }
    
    /**
     * 显示从 RequestEngine 获取的路由信息
     */
    private fun showRegisteredRoutes() {}

    override suspend fun stop() {
        gracefulShutdown()
    }

    private fun addShutdownHandler() {
        try {
        } catch (e: Exception) {
        }
    }

    private fun gracefulShutdown() {
        try {
            if (embeddedServer != null) {
                embeddedServer?.stop(gracePeriodMillis = 2000, timeoutMillis = 5000)
            } else {
            }
        } catch (e: Exception) {
            log()?.error("Error during graceful shutdown", emptyFields(), cause = e)
            embeddedServer = null
        } finally {
            embeddedServer = null
        }
    }
    
    /**
     * 统一处理路由请求。请求入口注入 LogContext；finally 打 access log（msg=http.access），异常打 http.error。
     */
    private suspend fun handleRoute(route: RouteDefinition, call: io.ktor.server.application.ApplicationCall) {
        val routeInfo = "${route.method.name} ${route.pattern} -> ${route.controllerClass}.${route.methodName}"
        val traceId = generateRequestTraceId()
        val logContext = LogContext(traceId = traceId, requestId = traceId, spanId = null, userId = null)
        val httpContext = KtorHttpContext(call, appContext, traceId)
        CurrentLogContext.set(logContext)
        val startMs = kotlin.time.Clock.System.now().toEpochMilliseconds()
        val method = call.request.httpMethod.value
        val path = call.request.uri.split("?").first()
        val bytesIn = call.request.contentLength() ?: 0L
        var status = 200
        val log = appContext?.getOrNull(LoggerFactory::class)?.get("neton.http")
        try {
            securityPreHandle(route, httpContext, path, method, call, log)
            val args = buildHandlerArgs(call, route.pattern)
            val result = route.handler.invoke(httpContext, args)
            // v1.1 方案 B：response.write 优先；已提交则不再用返回值 respond
            status = if (httpContext.response.isCommitted) {
                httpContext.response.status.code
            } else {
                handleResponse(call, result, routeInfo, log)
            }
        } catch (e: neton.core.http.ValidationException) {
            status = 400
            val body = neton.core.http.ErrorResponse(message = e.message ?: "Bad Request", errors = e.errors)
            call.respond(io.ktor.http.HttpStatusCode.BadRequest, body)
        } catch (e: neton.core.http.HttpException) {
            status = e.status.code
            log?.warn("http.error", fields = mapOf("method" to method, "path" to path, "status" to status, "traceId" to traceId), cause = e)
            val body = neton.core.http.ErrorResponse(message = e.message, errors = e.errors)
            call.respond(mapToKtorStatus(e.status), body)
        } catch (e: Exception) {
            status = 500
            log?.error("http.error", fields = mapOf("method" to method, "path" to path, "status" to status, "traceId" to traceId, "route" to routeInfo), cause = e)
            val body = neton.core.http.ErrorResponse(message = "Internal Server Error")
            call.respond(io.ktor.http.HttpStatusCode.InternalServerError, body)
        } finally {
            val latencyMs = kotlin.time.Clock.System.now().toEpochMilliseconds() - startMs
            val bytesOut = if (httpContext.response.isCommitted && httpContext.response is SimpleKtorHttpResponse) {
                (httpContext.response as SimpleKtorHttpResponse).lastBytesOut
            } else {
                0L
            }
            log?.info(
                "http.access",
                mapOf(
                    "method" to method,
                    "path" to path,
                    "routePattern" to route.pattern,
                    "status" to status,
                    "latencyMs" to latencyMs,
                    "bytesIn" to bytesIn,
                    "bytesOut" to bytesOut,
                    "traceId" to traceId
                )
            )
            CurrentLogContext.clear()
        }
    }

    /**
     * 安全预处理：认证 + 授权，principal 写入 httpContext.attributes["principal"]
     * v1.1：仅 AllowAnonymous / RequireAuth；Mode A（未安装 Security）时 @RequireAuth → 500
     */
    private suspend fun securityPreHandle(
        route: RouteDefinition,
        httpContext: HttpContext,
        path: String,
        method: String,
        call: io.ktor.server.application.ApplicationCall,
        log: neton.logging.Logger?
    ) {
        val allowAnonymous = route.allowAnonymous
        val requireAuth = route.requireAuth
        val securityConfig = appContext?.getOrNull(SecurityConfiguration::class)
        val reqHeaders = mutableMapOf<String, String>().apply {
            call.request.headers.forEach { name, values -> values.firstOrNull()?.let { put(name, it) } }
        }
        val configuredGroups = appContext?.getOrNull(ConfiguredRouteGroups::class)?.names ?: emptySet()
        val routeGroup = route.routeGroup
            ?: inferRouteGroup(route.controllerClass, configuredGroups)
        val requestContext = KtorRequestContext(
            path = path,
            method = method,
            headers = reqHeaders,
            routeGroup = routeGroup
        )

        try {
            runSecurityPreHandle(route, httpContext, requestContext, securityConfig)
        } catch (e: HttpException) {
            when (e.status) {
                HttpStatus.INTERNAL_SERVER_ERROR -> log?.warn("security.config.error", mapOf("message" to e.message))
                HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN -> { /* 401/403 正常业务拒绝 */ }
                else -> {}
            }
            throw e
        }
    }

    /**
     * 从控制器全限定名推断 routeGroup（包名最后一段）。
     * 仅当 candidate 在 configuredGroups 中时才返回，否则 null。
     * v1.1 将由 KSP/路由层写入 RouteDefinition.routeGroup，此函数仅作 P0 fallback。
     */
    private fun inferRouteGroup(controllerClass: String?, configuredGroups: Set<String>): String? {
        if (controllerClass == null || configuredGroups.isEmpty()) return null
        val segments = controllerClass.split(".")
        if (segments.size < 2) return null
        val lastPackageSegment = segments[segments.lastIndex - 1]
        return if (lastPackageSegment in configuredGroups) lastPackageSegment else null
    }

    private fun generateRequestTraceId(): String {
        val ts = kotlin.time.Clock.System.now().toEpochMilliseconds()
        val r = (0 until 100000).random()
        return "req-$ts-$r"
    }
    
    private fun mapToKtorStatus(s: neton.core.http.HttpStatus): io.ktor.http.HttpStatusCode = when (s) {
        neton.core.http.HttpStatus.BAD_REQUEST -> io.ktor.http.HttpStatusCode.BadRequest
        neton.core.http.HttpStatus.UNAUTHORIZED -> io.ktor.http.HttpStatusCode.Unauthorized
        neton.core.http.HttpStatus.FORBIDDEN -> io.ktor.http.HttpStatusCode.Forbidden
        neton.core.http.HttpStatus.NOT_FOUND -> io.ktor.http.HttpStatusCode.NotFound
        neton.core.http.HttpStatus.CONFLICT -> io.ktor.http.HttpStatusCode.Conflict
        neton.core.http.HttpStatus.UNSUPPORTED_MEDIA_TYPE -> io.ktor.http.HttpStatusCode.UnsupportedMediaType
        else -> io.ktor.http.HttpStatusCode(s.code, s.message)
    }

    /**
     * 构建 ArgsView：path 与 query 分离，零 merge（规范 v1.0.2）
     * path 仅含 pattern 中 {param} 的路径参数；query 来自 URL 查询字符串。
     * 之前误将 call.parameters 全放入 path，导致 query 参数（如 tags）被跳过，List 绑定失败。
     */
    private fun buildHandlerArgs(call: io.ktor.server.application.ApplicationCall, pattern: String): ArgsView {
        val path = mutableMapOf<String, String>()
        val query = mutableMapOf<String, List<String>>()
        val pathParamNames = Regex("\\{([^}]+)\\}").findAll(pattern).map { it.groupValues[1] }.toSet()
        try {
            if (pathParamNames.isNotEmpty()) {
                call.parameters.forEach { key, values ->
                    if (key in pathParamNames) values.firstOrNull()?.let { path[key] = it }
                }
                if (path.isEmpty()) {
                    val pathSegments = call.request.uri.split("?")[0].split("/")
                    val patternSegments = pattern.split("/")
                    for (i in patternSegments.indices) {
                        if (i >= pathSegments.size) break
                        val segment = patternSegments[i]
                        val pathValue = pathSegments[i]
                        val paramsInSegment = Regex("\\{([^}]+)\\}").findAll(segment).map { it.groupValues[1] }.toList()
                        when {
                            paramsInSegment.size == 1 -> path[paramsInSegment[0]] = pathValue
                            paramsInSegment.size > 1 -> {
                                val literals = segment.split(Regex("\\{[^}]+\\}")).filter { it.isNotEmpty() }
                                val parts = if (literals.isEmpty()) listOf(pathValue)
                                else pathValue.split(Regex(literals.joinToString("|") { Regex.escape(it) }))
                                paramsInSegment.forEachIndexed { idx, name ->
                                    if (idx < parts.size) path[name] = parts[idx]
                                }
                            }
                            else -> {}
                        }
                    }
                }
            }
            val qp = call.request.queryParameters
            qp.names().forEach { key ->
                query[key] = qp.getAll(key) ?: emptyList()
            }
        } catch (e: Exception) {
            log()?.warn("Handler args build failed", mapOf("pattern" to pattern), cause = e)
        }
        return ArgsView(path, query)
    }
    
    
    /** Map/List 转 JSON 字符串，避免 Ktor ContentNegotiation 对 Map<String,Any> 序列化失败 */
    private fun mapToJsonString(obj: Any): String = when (obj) {
        is Map<*, *> -> obj.entries.joinToString(",", "{", "}") { (k, v) ->
            "\"${k.toString().replace("\\", "\\\\").replace("\"", "\\\"")}\":${if (v != null) mapToJsonString(v) else "null"}"
        }
        is List<*> -> obj.joinToString(",", "[", "]") { if (it != null) mapToJsonString(it) else "null" }
        is String -> "\"${obj.replace("\\", "\\\\").replace("\"", "\\\"")}\""
        is Number -> obj.toString()
        is Boolean -> obj.toString()
        else -> "\"${obj}\""
    }
    
    /**
     * 处理控制器方法返回值，返回 HTTP 状态码（用于 access log）。
     */
    private suspend fun handleResponse(call: io.ktor.server.application.ApplicationCall, result: Any?, routeInfo: String, log: neton.logging.Logger?): Int {
        return try {
            when (result) {
                null, is Unit -> {
                    call.respond(HttpStatusCode.NoContent)
                    204
                }
                is String -> {
                    call.respondText(result, ContentType.Text.Plain)
                    200
                }
                is Map<*, *> -> {
                    val json = mapToJsonString(result)
                    call.respondText(json, ContentType.Application.Json)
                    200
                }
                is Number -> {
                    call.respondText(result.toString(), ContentType.Text.Plain)
                    200
                }
                is Boolean -> {
                    call.respondText(result.toString(), ContentType.Text.Plain)
                    200
                }
                else -> {
                    call.respond(result)
                    200
                }
            }
        } catch (e: Exception) {
            log?.warn("response failed", fields = mapOf("route" to routeInfo), cause = e)
            call.respond(HttpStatusCode.InternalServerError, "响应处理错误")
            500
        }
    }
}

/**
 * Ktor HttpContext 适配器 - 将 Ktor ApplicationCall 转换为 Neton HttpContext。
 * traceId 由请求入口生成并与 LogContext 一致，供 APM/日志串联。
 */
private class KtorHttpContext(
    private val call: io.ktor.server.application.ApplicationCall,
    private val netonContext: NetonContext?,
    override val traceId: String
) : HttpContext {
    override val request: HttpRequest = SimpleKtorHttpRequest(call)
    override val response: HttpResponse = SimpleKtorHttpResponse(call)
    override val session: HttpSession = SimpleKtorHttpSession()
    override val attributes: MutableMap<String, Any> = mutableMapOf()

    override fun getApplicationContext(): NetonContext? = netonContext
}

/**
 * 简化的 Ktor HttpRequest 适配器
 */  
private class SimpleKtorHttpRequest(private val call: io.ktor.server.application.ApplicationCall) : HttpRequest {
    
    override suspend fun body(): ByteArray = call.receiveChannel().readRemaining().readByteArray()
    
    override suspend fun text(): String = body().decodeToString()
    
    override suspend fun json(): Any = mapOf<String, Any>() // @Body 使用 context.request.text() + Json.decodeFromString
    
    override suspend fun form(): neton.core.http.Parameters = SimpleParameters()
    
    override val method: neton.core.http.HttpMethod = when (call.request.httpMethod.value) {
        "GET" -> neton.core.http.HttpMethod.GET
        "POST" -> neton.core.http.HttpMethod.POST
        "PUT" -> neton.core.http.HttpMethod.PUT
        "DELETE" -> neton.core.http.HttpMethod.DELETE
        else -> neton.core.http.HttpMethod.GET
    }
    
    override val path: String = call.request.uri
    override val url: String = call.request.uri
    override val version: String = "HTTP/1.1"
    override val pathParams: neton.core.http.Parameters = SimpleParameters()
    override val queryParams: neton.core.http.Parameters = SimpleParameters()
    override val headers: neton.core.http.Headers = SimpleHeaders()
    override val cookies: Map<String, neton.core.http.Cookie> = emptyMap()
    override val remoteAddress: String = "127.0.0.1"
    override val isSecure: Boolean = false
}

/**
 * 简化的 HttpResponse 适配器。v1.1 方案 B：所有“提交”入口（write/text/json/redirect/error 等）统一置 isCommitted；
 * commit 后禁止二次写（fail-fast）；status 在 commit 前由各 API 设置，access log 用 response.status.code。
 */
private class SimpleKtorHttpResponse(private val call: io.ktor.server.application.ApplicationCall) : HttpResponse {

    override val headers: neton.core.http.MutableHeaders = SimpleMutableHeaders()

    override var status: neton.core.http.HttpStatus = neton.core.http.HttpStatus.OK

    override fun cookie(cookie: neton.core.http.Cookie) {}

    private var _committed = false
    override val isCommitted: Boolean get() = _committed

    /** committed 路径下写入的 body 字节数，供 access log bytesOut 使用；未 commit 时为 0。 */
    var lastBytesOut: Long = 0L
        private set

    private fun ensureNotCommitted() {
        if (_committed) throw neton.core.http.HttpException(
            neton.core.http.HttpStatus.INTERNAL_SERVER_ERROR,
            "Response already committed (ResponseAlreadyCommitted)"
        )
    }

    override suspend fun write(data: ByteArray) {
        ensureNotCommitted()
        _committed = true
        lastBytesOut = data.size.toLong()
        val ct = ContentType.parse(contentType ?: "application/octet-stream")
        call.respondBytes(data, ct, HttpStatusCode.fromValue(status.code))
    }

    /** redirect() 在 core 中不调用 write()，必须在此实现中显式 commit 并发送重定向。 */
    override suspend fun redirect(url: String, status: neton.core.http.HttpStatus) {
        ensureNotCommitted()
        _committed = true
        this.status = status
        header("Location", url)
        val permanent = status == neton.core.http.HttpStatus.MOVED_PERMANENTLY
        call.respondRedirect(url, permanent)
    }
}

/**
 * 简化的 HttpSession 适配器
 */
private class SimpleKtorHttpSession : HttpSession {
    
    private val data = mutableMapOf<String, Any>()
    
    override fun getAttribute(name: String): Any? = data[name]
    
    override fun setAttribute(name: String, value: Any?) { 
        if (value != null) data[name] = value 
    }
    
    override fun removeAttribute(name: String): Any? = data.remove(name)
    
    override fun getAttributeNames(): Set<String> = data.keys
    
    override fun invalidate() { data.clear() }
    
    override fun touch() {}
    
    override val id: String = "simple-session"
    override val creationTime: Long = 0L
    override val lastAccessTime: Long = 0L
    override var maxInactiveInterval: Int = 1800
    override val isNew: Boolean = true
    override val isValid: Boolean = true
}

/**
 * 简化的 Parameters 实现
 */
private class SimpleParameters : neton.core.http.Parameters {
    override fun get(name: String): String? = null
    override fun getAll(name: String): List<String> = emptyList()
    override fun contains(name: String): Boolean = false
    override fun names(): Set<String> = emptySet()
    override fun toMap(): Map<String, List<String>> = emptyMap()
}

/**
 * 简化的 Headers 实现
 */
private class SimpleHeaders : neton.core.http.Headers {
    override fun get(name: String): String? = null
    override fun getAll(name: String): List<String> = emptyList()
    override fun contains(name: String): Boolean = false
    override fun names(): Set<String> = emptySet()
    override fun toMap(): Map<String, List<String>> = emptyMap()
}

/**
 * 简化的 MutableHeaders 实现 
 */
private class SimpleMutableHeaders : neton.core.http.MutableHeaders {
    override fun get(name: String): String? = null
    override fun getAll(name: String): List<String> = emptyList()
    override fun contains(name: String): Boolean = false
    override fun names(): Set<String> = emptySet()
    override fun toMap(): Map<String, List<String>> = emptyMap()
    override fun set(name: String, value: String) {}
    override fun add(name: String, value: String) {}
    override fun remove(name: String) {}
    override fun clear() {}
} 