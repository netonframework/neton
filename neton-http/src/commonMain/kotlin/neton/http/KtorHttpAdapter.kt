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
import io.ktor.http.content.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.http.*
import io.ktor.http.cio.MultipartEvent
import io.ktor.http.cio.parseMultipart
import io.ktor.utils.io.readRemaining
import io.ktor.utils.io.writeFully
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
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
    private var backgroundJob: Job? = null
    private var backgroundScope: CoroutineScope? = null

    override fun port(): Int = serverConfig.port
    override fun adapterName(): String = "Ktor CIO"

    private fun log(): neton.logging.Logger? = appContext?.getOrNull(LoggerFactory::class)?.get("neton.http")

    override suspend fun start(ctx: NetonContext, onStarted: (suspend (coldStartMs: Long) -> Unit)?) {
        appContext = ctx
        requestEngine = ctx.getOrNull(RequestEngine::class)
        val job = SupervisorJob()
        backgroundJob = job
        backgroundScope = CoroutineScope(job + Dispatchers.Default)
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

    private suspend fun run(port: Int, args: Array<String>, onStarted: (suspend (coldStartMs: Long) -> Unit)? = null) {
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

                    // CORS — 注意 ktor-server-cors 3.4.2 的几个坑（都已经
                    // 踩过一遍）：
                    //   1) allowHost 要求 host 跟 scheme 分开传，
                    //      传完整 URL 会抛 IllegalArgumentException。
                    //   2) 必须显式打开 allowNonSimpleContentTypes，否则
                    //      JSON API（content-type: application/json 是
                    //      non-simple）的 actual request 即使预检通过
                    //      也会被拒 403。
                    //   3) 必须显式 allowMethod 列出 POST / PUT / PATCH 等，
                    //      anyMethod() 的覆盖范围在某些 actual-request
                    //      路径上不可靠。
                    serverConfig.corsConfig?.let { cors ->
                        install(CORS) {
                            cors.allowedOrigins.forEach { origin ->
                                when {
                                    origin == "*" -> anyHost()
                                    origin.contains("://") -> {
                                        val sep = origin.indexOf("://")
                                        val scheme = origin.substring(0, sep)
                                        val hostPort = origin.substring(sep + 3)
                                        allowHost(hostPort, schemes = listOf(scheme))
                                    }
                                    else -> allowHost(origin)
                                }
                            }
                            cors.allowedMethods.forEach { m ->
                                allowMethod(io.ktor.http.HttpMethod.parse(m))
                            }
                            cors.allowedHeaders.forEach { h ->
                                if (h == "*") allowHeaders { true } else allowHeader(h)
                            }
                            allowNonSimpleContentTypes = true
                            allowSameOrigin = true
                            if (cors.allowCredentials) allowCredentials = true
                            maxAgeInSeconds = cors.maxAgeSeconds
                        }
                    }

                    routing {
                        // === 动态注册控制器路由 ===
                        val engine = requestEngine
                        if (engine != null) {
                            val routes = engine.getRoutes()
                            val groupMounts = appContext?.getOrNull(RouteGroupMounts::class)?.groupToMount ?: emptyMap()
                            val configuredGroups =
                                appContext?.getOrNull(ConfiguredRouteGroups::class)?.names ?: emptySet()

                            val rootRoute = this
                            val routesByGroup = routes.groupBy { route ->
                                route.routeGroup ?: inferRouteGroup(route.controllerClass, configuredGroups)
                            }
                            // 无 mount 的默认组优先注册，确保 get("/") 在 route("{...}") 之前
                            val ordered = routesByGroup.entries.sortedBy { (g, _) -> if (g == null) 0 else 1 }
                            ordered.forEach { (group, groupRoutes) ->
                                val mount =
                                    if (group != null) groupMounts[group]?.takeIf { it.isNotEmpty() } ?: "/$group" else ""
                                // 更具体的路径优先注册（否则根路径 get("/") 可能贪婪匹配）
                                val sorted =
                                    groupRoutes.sortedBy { if (it.pattern == "/" || it.pattern == "") 1 else 0 }
                                val registerBlock: io.ktor.server.routing.Route.() -> Unit = {
                                    sorted.forEach { route ->
                                        // 嵌套 route(mount) 内需用相对路径，去掉首斜杠
                                        var path = if (mount.isNotEmpty() && route.pattern.startsWith("/")) {
                                            route.pattern.removePrefix("/")
                                        } else {
                                            route.pattern
                                        }
                                        // 根路径 "" 需同时注册 get("/") 以匹配带尾斜杠的 /admin/
                                        val paths =
                                            if (mount.isNotEmpty() && path == "") listOf("", "/") else listOf(path)
                                        paths.forEach { p ->
                                            when (route.method.name) {
                                                "GET" -> get(p) { handleRoute(route, call) }
                                                "POST" -> post(p) { handleRoute(route, call) }
                                                "PUT" -> put(p) { handleRoute(route, call) }
                                                "DELETE" -> delete(p) { handleRoute(route, call) }
                                                "PATCH" -> patch(p) { handleRoute(route, call) }
                                                "HEAD" -> head(p) { handleRoute(route, call) }
                                                "OPTIONS" -> options(p) { handleRoute(route, call) }
                                                else -> { /* unsupported method */
                                                }
                                            }
                                        }
                                    }
                                }
                                if (mount.isNotEmpty()) {
                                    val mountPath = mount.trimStart('/')
                                    // 扁平化：route("admin/index") { get { } } 避免嵌套路径匹配问题
                                    sorted.forEach { route ->
                                        val rel =
                                            if (route.pattern.startsWith("/")) route.pattern.removePrefix("/") else route.pattern
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
                                                else -> { /* unsupported method */
                                                }
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
                    }
                    gracefulShutdown(propagateFailure = false)
                    throw e
                }

            } catch (e: Throwable) {
                if (isPortInUse(e)) {
                    kotlin.io.println("Port $port is already in use. Stop the other process or use a different port.")
                }
                throw e
            }
        } catch (e: Throwable) {
            if (isPortInUse(e)) {
                kotlin.io.println("Port $port is already in use. Stop the other process or use a different port.")
            }
            log()?.error("Failed to start Ktor server", mapOf("port" to port), cause = e)
            gracefulShutdown(propagateFailure = false)
            throw e
        } finally {
            // 确保在任何情况下都执行清理
            // 如果是正常退出（比如 Ctrl+C），也执行优雅关闭
            if (embeddedServer != null) {
                gracefulShutdown(propagateFailure = false)
            }
        }
    }

    /**
     * 显示从 RequestEngine 获取的路由信息
     */
    private fun showRegisteredRoutes() {}

    override suspend fun stop() {
        try {
            gracefulShutdown(propagateFailure = true)
        } finally {
            backgroundScope = null
            backgroundJob?.cancelAndJoin()
            backgroundJob = null
            appContext = null
            requestEngine = null
        }
    }

    private fun gracefulShutdown(propagateFailure: Boolean) {
        try {
            if (embeddedServer != null) {
                embeddedServer?.stop(gracePeriodMillis = 2000, timeoutMillis = 5000)
            } else {
            }
        } catch (e: Exception) {
            log()?.error("Error during graceful shutdown", emptyFields(), cause = e)
            if (propagateFailure) throw e
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
            // ValidationException 默认走 InvalidParams（spec ERROR_CODE_SPEC §3）；业务侧需要更细错误码请直接 throw HttpException(code, ...)
            respondEnvelope(
                call,
                io.ktor.http.HttpStatusCode.BadRequest,
                neton.core.http.ApiEnvelope.error(
                    neton.core.http.NetonErrorCode.INVALID_PARAMS,
                    e.message,
                ),
            )
        } catch (e: neton.core.http.HttpException) {
            // body.code 是权威，HTTP status 由 framework 内部 [httpStatusForErrorCode] 推导
            val httpStatus = neton.core.http.httpStatusForErrorCode(e.code)
            status = httpStatus.code
            log?.warn(
                "http.error",
                fields = mapOf("method" to method, "path" to path, "status" to status, "traceId" to traceId),
                cause = e
            )
            respondEnvelope(
                call,
                mapToKtorStatus(httpStatus),
                neton.core.http.ApiEnvelope.error(e.code, e.message),
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 客户端断连/协程取消：不是业务错误，向上冒泡由引擎收尾（流式响应已提交时不可再写）
            status = httpContext.response.status.code
            throw e
        } catch (e: Exception) {
            if (httpContext.response.isCommitted) {
                // 响应已提交（典型：流式写出中客户端断连 Broken pipe）——无法再回 envelope，按 WARN 收尾
                status = httpContext.response.status.code
                log?.warn(
                    "http.stream.aborted",
                    fields = mapOf("method" to method, "path" to path, "traceId" to traceId),
                    cause = e
                )
                return
            }
            status = 500
            log?.error(
                "http.error",
                fields = mapOf(
                    "method" to method,
                    "path" to path,
                    "status" to status,
                    "traceId" to traceId,
                    "route" to routeInfo
                ),
                cause = e
            )
            // 异步写入 API 错误日志到数据库
            val errorLogWriter = appContext?.getOrNull(neton.core.interfaces.ErrorLogWriter::class)
            if (errorLogWriter != null) {
                val identity = httpContext.attributes["identity"] as? Identity
                val userIp = call.request.local.remoteAddress
                val userAgent = call.request.headers["User-Agent"]
                val queryString = call.request.queryString()
                val requestParams = if (queryString.isNotEmpty()) queryString else null
                val errorEntry = neton.core.interfaces.ErrorLogEntry(
                    userId = identity?.id?.toLongOrNull(),
                    userType = if (identity != null) 2 else 0,
                    applicationName = "neton-application",
                    requestMethod = method,
                    requestUrl = path,
                    requestParams = requestParams,
                    userIp = userIp,
                    userAgent = userAgent,
                    exceptionName = e::class.simpleName ?: "Exception",
                    exceptionMessage = e.message,
                    exceptionStackTrace = e.stackTraceToString()
                )
                backgroundScope?.launch {
                    try {
                        errorLogWriter.write(errorEntry)
                    } catch (writeEx: Exception) {
                        log?.warn("error-log.write.failed", mapOf("path" to path), cause = writeEx)
                    }
                }
            }
            // 兜底未捕获异常 → InternalError（spec ERROR_CODE_SPEC system 段），HTTP 仍 500
            respondEnvelope(
                call,
                io.ktor.http.HttpStatusCode.InternalServerError,
                neton.core.http.ApiEnvelope.error(
                    neton.core.http.NetonErrorCode.INTERNAL_ERROR,
                    "Internal Server Error",
                ),
            )
        } finally {
            val endMs = kotlin.time.Clock.System.now().toEpochMilliseconds()
            val latencyMs = endMs - startMs
            val bytesOut = if (httpContext.response.isCommitted && httpContext.response is SimpleKtorHttpResponse) {
                httpContext.response.lastBytesOut
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
            // 异步写入 API 访问日志到数据库
            val accessLogWriter = appContext?.getOrNull(neton.core.interfaces.AccessLogWriter::class)
            if (accessLogWriter != null) {
                val identity = httpContext.attributes["identity"] as? Identity
                val userIp = call.request.local.remoteAddress
                val userAgent = call.request.headers["User-Agent"]
                val queryString = call.request.queryString()
                val requestParams = if (queryString.isNotEmpty()) queryString else null
                val entry = neton.core.interfaces.AccessLogEntry(
                    userId = identity?.id?.toLongOrNull(),
                    userType = if (identity != null) 2 else 0,
                    applicationName = "neton-application",
                    requestMethod = method,
                    requestUrl = path,
                    requestParams = requestParams,
                    userIp = userIp,
                    userAgent = userAgent,
                    beginTime = startMs,
                    endTime = endMs,
                    duration = latencyMs,
                    resultCode = status,
                    resultMsg = null
                )
                backgroundScope?.launch {
                    try {
                        accessLogWriter.write(entry)
                    } catch (e: Exception) {
                        log?.warn("access-log.write.failed", mapOf("path" to path), cause = e)
                    }
                }
            }
            CurrentLogContext.clear()
        }
    }

    /**
     * 安全预处理：认证 + 授权，identity 写入 httpContext.attributes["identity"]
     * v1.2：Identity 体系 + @Permission + PermissionEvaluator + 路由组白名单
     */
    private suspend fun securityPreHandle(
        route: RouteDefinition,
        httpContext: HttpContext,
        path: String,
        method: String,
        call: io.ktor.server.application.ApplicationCall,
        log: neton.logging.Logger?
    ) {
        val securityConfig = appContext?.getOrNull(SecurityConfiguration::class)
        val routeGroupSecurityConfigs = appContext?.getOrNull(RouteGroupSecurityConfigs::class)
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
            runSecurityPreHandle(route, httpContext, requestContext, securityConfig, routeGroupSecurityConfigs)
        } catch (e: HttpException) {
            when (neton.core.http.httpStatusForErrorCode(e.code)) {
                HttpStatus.INTERNAL_SERVER_ERROR -> log?.warn("security.config.error", mapOf("message" to e.message))
                HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN -> { /* 401/403 正常业务拒绝 */
                }

                else -> {}
            }
            throw e
        }
    }

    /**
     * 从控制器全限定名推断 routeGroup。
     * 扫描包路径中所有段，返回第一个匹配 configuredGroups 的段。
     * 例如 controller.admin.auth.AuthController → "admin"
     */
    private fun inferRouteGroup(controllerClass: String?, configuredGroups: Set<String>): String? {
        if (controllerClass == null || configuredGroups.isEmpty()) return null
        val segments = controllerClass.split(".")
        if (segments.size < 2) return null
        // 扫描所有包段（排除最后一段即类名），返回第一个匹配的 group
        for (i in 0 until segments.lastIndex) {
            if (segments[i] in configuredGroups) return segments[i]
        }
        return null
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


    /**
     * Envelope 序列化用的 Json 实例。
     * - `encodeDefaults=true` + `explicitNulls=true`：错误响应必须显式 `data: null`（spec §2.3）。
     */
    private val envelopeJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = true
    }

    /** 把 Map / List / 原始类型转为 JsonElement（业务 @Serializable 对象由 KSP 预序列化为 JsonContent）。 */
    private fun valueToJsonElement(v: Any?): kotlinx.serialization.json.JsonElement = when (v) {
        null -> kotlinx.serialization.json.JsonNull
        is kotlinx.serialization.json.JsonElement -> v
        is String -> kotlinx.serialization.json.JsonPrimitive(v)
        is Number -> kotlinx.serialization.json.JsonPrimitive(v)
        is Boolean -> kotlinx.serialization.json.JsonPrimitive(v)
        is Map<*, *> -> kotlinx.serialization.json.buildJsonObject {
            for ((k, value) in v) put(k.toString(), valueToJsonElement(value))
        }
        is List<*> -> kotlinx.serialization.json.buildJsonArray {
            for (item in v) add(valueToJsonElement(item))
        }
        else -> kotlinx.serialization.json.JsonPrimitive(v.toString())
    }

    /** 写入统一信封响应（spec SERVICE_RESPONSE_ENVELOPE_SPEC §2）。 */
    private suspend fun respondEnvelope(
        call: io.ktor.server.application.ApplicationCall,
        httpStatus: io.ktor.http.HttpStatusCode,
        envelope: neton.core.http.ApiEnvelope,
    ) {
        val text = envelopeJson.encodeToString(neton.core.http.ApiEnvelope.serializer(), envelope)
        call.respondText(text, ContentType.Application.Json, httpStatus)
    }

    /**
     * 处理控制器方法返回值，把业务 data 包装为统一信封后写出。
     */
    private suspend fun handleResponse(
        call: io.ktor.server.application.ApplicationCall,
        result: Any?,
        routeInfo: String,
        log: neton.logging.Logger?
    ): Int {
        return try {
            // KSP 已把 @Serializable result 序列化为 JsonContent；framework 这里只把 data 包入信封。
            val data: kotlinx.serialization.json.JsonElement = when (result) {
                null, is Unit -> kotlinx.serialization.json.JsonNull
                is neton.core.http.JsonContent -> envelopeJson.parseToJsonElement(result.json)
                else -> valueToJsonElement(result)
            }
            respondEnvelope(call, io.ktor.http.HttpStatusCode.OK, neton.core.http.ApiEnvelope.ok(data))
            200
        } catch (e: Exception) {
            log?.warn("response failed", fields = mapOf("route" to routeInfo), cause = e)
            respondEnvelope(
                call,
                io.ktor.http.HttpStatusCode.InternalServerError,
                neton.core.http.ApiEnvelope.error(
                    neton.core.http.NetonErrorCode.INTERNAL_ERROR,
                    "Internal Server Error",
                ),
            )
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
 * 简化的 Ktor HttpRequest 适配器。
 *
 * Multipart / form 解析必须**绕过 ContentNegotiation**：Ktor 3.x 的
 * `install(ContentNegotiation) { json {} }` 接管整个 receive transform 链，
 * `call.receiveMultipart()` 内部走 `receiveNullable<MultiPartData>` 也会被
 * 拦截抛 `CannotTransformContentToTypeException`。这里用 `ktor-http-cio` 的
 * 低阶 [parseMultipart] 直接读 [io.ktor.utils.io.ByteReadChannel]，跳过整个
 * ContentNegotiation 链；同时缓存解析结果，让同一请求里 `uploadFiles()` 与
 * `form()` 都能拿到对应的部分（multipart 流是一次性消费的，必须缓存）。
 */
private class SimpleKtorHttpRequest(private val call: io.ktor.server.application.ApplicationCall) : HttpRequest {

    /** multipart 解析结果缓存：同一请求内 uploadFiles() / form() 复用同一份字节解析。 */
    private var parsedMultipartFiles: neton.core.http.UploadFiles? = null
    private var parsedMultipartForm: neton.core.http.Parameters? = null

    override suspend fun body(): ByteArray = call.receiveChannel().readRemaining().readByteArray()

    override suspend fun text(): String = body().decodeToString()

    override suspend fun json(): Any = mapOf<String, Any>() // @Body 使用 context.request.text() + Json.decodeFromString

    override suspend fun form(): neton.core.http.Parameters {
        val ct = call.request.contentType()
        return when {
            ct.match(ContentType.MultiPart.FormData) -> {
                ensureMultipartParsed()
                parsedMultipartForm ?: SimpleParameters()
            }
            ct.match(ContentType.Application.FormUrlEncoded) -> {
                // urlencoded 路径也不走 ContentNegotiation：直接读字节解析。
                val raw = call.receiveChannel().readRemaining().readByteArray().decodeToString()
                MapParameters(parseQueryString(raw))
            }
            else -> SimpleParameters()
        }
    }

    override suspend fun uploadFiles(): neton.core.http.UploadFiles {
        ensureMultipartParsed()
        return parsedMultipartFiles ?: neton.core.http.UploadFiles(emptyList())
    }

    /**
     * 一次性消费 multipart body，分别填充 [parsedMultipartFiles] 与
     * [parsedMultipartForm]。非 multipart 请求短路返回空。
     */
    private suspend fun ensureMultipartParsed() {
        if (parsedMultipartFiles != null) return
        val ct = call.request.contentType()
        if (!ct.match(ContentType.MultiPart.FormData)) {
            parsedMultipartFiles = neton.core.http.UploadFiles(emptyList())
            parsedMultipartForm = SimpleParameters()
            return
        }

        val files = mutableListOf<neton.core.http.UploadFile>()
        val formFields = mutableMapOf<String, MutableList<String>>()

        // CoroutineScope.parseMultipart 把生产者协程拍到当前 scope 里；用 coroutineScope { }
        // 在 ensureMultipartParsed 自身的 suspend 上下文里建一份父子结构清晰的 scope。
        coroutineScope {
            val channel = call.receiveChannel()
            val contentLength = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            val events = parseMultipart(channel, ct.toString(), contentLength)
            for (event in events) {
                if (event !is MultipartEvent.MultipartPart) continue
                val rawHeaders = event.headers.await()
                val disposition = rawHeaders["Content-Disposition"]?.toString().orEmpty()
                val partContentType = rawHeaders["Content-Type"]?.toString()
                val name = NAME_REGEX.find(disposition)
                    ?.groupValues?.let { g -> g[1].ifEmpty { g[2] } }
                    .orEmpty()
                // filename 提取三级兜底:标准 filename= → RFC 5987 filename*=(部分客户端对
                // 非 ASCII 名只发这个)→ 有独立 Content-Type 的 part 视为文件(某些客户端
                // 特殊字符文件名会破坏 disposition,不能因此把文件当表单字段丢掉)。
                val filename = FILENAME_REGEX.find(disposition)
                    ?.groupValues?.let { g -> g[1].ifEmpty { g[2] } }?.takeIf { it.isNotEmpty() }
                    ?: FILENAME_STAR_REGEX.find(disposition)?.groupValues?.get(1)?.let { encoded ->
                        encoded.substringAfterLast("''").ifEmpty { encoded }
                    }
                    ?: if (partContentType != null && !partContentType.startsWith("text/plain")) "upload" else null
                val bytes = event.body.readRemaining().readByteArray()
                if (filename != null) {
                    files.add(
                        KtorUploadFile(
                            fieldName = name,
                            filename = filename,
                            contentType = partContentType,
                            size = bytes.size.toLong(),
                            data = bytes,
                        ),
                    )
                } else if (name.isNotEmpty()) {
                    formFields.getOrPut(name) { mutableListOf() }.add(bytes.decodeToString())
                }
                // bytes 已读完，body 通道自然耗尽；headers Deferred await 后无需手动释放。
            }
        }

        parsedMultipartFiles = neton.core.http.UploadFiles(files)
        parsedMultipartForm = MapParameters(formFields)
    }

    private companion object {
        // Content-Disposition: form-data; name="businessType"
        // Content-Disposition: form-data; name="file"; filename="avatar.png"
        // Content-Disposition: form-data; name=file; filename="avatar.png"
        //   —— Ktor client 的 escapeIfNeeded() 对纯 token 不加引号(RFC 合法),
        //   name/filename 都必须同时接受带引号与 token 两种形态。
        private val NAME_REGEX = Regex("""name=(?:"([^"]*)"|([^";\s]+))""")
        private val FILENAME_REGEX = Regex("""filename=(?:"([^"]*)"|([^";\s]+))""")

        // RFC 5987: filename*=UTF-8''%E5%9B%BE.jpg（不带引号）
        private val FILENAME_STAR_REGEX = Regex("""filename\*=([^;\s]+)""")
    }

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
    override val queryParams: neton.core.http.Parameters = KtorQueryParameters(call)
    override val headers: neton.core.http.Headers = KtorRequestHeaders(call)
    override val cookies: Map<String, neton.core.http.Cookie> = emptyMap()
    override val remoteAddress: String = call.request.local.remoteAddress
    override val isSecure: Boolean = call.request.local.scheme == "https"
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
            neton.core.http.NetonErrorCode.INTERNAL_ERROR,
            "Response already committed (ResponseAlreadyCommitted)"
        )
    }

    /** 把业务侧 header() 设置的响应头应用到 Ktor（Content-Type/Length 由引擎管理，跳过）。 */
    private fun applyHeadersToCall() {
        for (name in headers.names()) {
            if (name.equals("Content-Type", ignoreCase = true) || name.equals("Content-Length", ignoreCase = true)) continue
            for (value in headers.getAll(name)) call.response.headers.append(name, value)
        }
    }

    override suspend fun write(data: ByteArray) {
        ensureNotCommitted()
        _committed = true
        lastBytesOut = data.size.toLong()
        applyHeadersToCall()
        val ct = ContentType.parse(contentType ?: "application/octet-stream")
        call.respondBytes(data, ct, HttpStatusCode.fromValue(status.code))
    }

    /**
     * 真流式写出：respondBytesWriter 逐块 writeFully+flush。
     * 客户端断连时写通道抛取消/IO 异常，向上冒泡以取消 block 内的上游拉取。
     */
    override suspend fun stream(block: suspend neton.core.http.HttpBodyWriter.() -> Unit) {
        ensureNotCommitted()
        _committed = true
        applyHeadersToCall()
        val ct = ContentType.parse(contentType ?: "application/octet-stream")
        var bytesOut = 0L
        call.respondBytesWriter(contentType = ct, status = HttpStatusCode.fromValue(status.code)) {
            val channel = this
            val writer = object : neton.core.http.HttpBodyWriter {
                override suspend fun writeChunk(chunk: ByteArray) {
                    channel.writeFully(chunk, 0, chunk.size)
                    channel.flush()
                    bytesOut += chunk.size
                }
            }
            writer.block()
        }
        lastBytesOut = bytesOut
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

    override fun invalidate() {
        data.clear()
    }

    override fun touch() {}

    override val id: String = "simple-session"
    override val creationTime: Long = 0L
    override val lastAccessTime: Long = 0L
    override var maxInactiveInterval: Int = 1800
    override val isNew: Boolean = true
    override val isValid: Boolean = true
}

/**
 * Ktor 上传文件实现
 */
private class KtorUploadFile(
    override val fieldName: String,
    override val filename: String,
    override val contentType: String?,
    override val size: Long,
    private val data: ByteArray
) : neton.core.http.UploadFile {
    override suspend fun bytes(): ByteArray = data
}

/**
 * 简化的 Parameters 实现 —— 永远空集，用作 fallback。
 */
private class SimpleParameters : neton.core.http.Parameters {
    override fun get(name: String): String? = null
    override fun getAll(name: String): List<String> = emptyList()
    override fun contains(name: String): Boolean = false
    override fun names(): Set<String> = emptySet()
    override fun toMap(): Map<String, List<String>> = emptyMap()
}

/**
 * 通用 [Map]-backed [neton.core.http.Parameters]。multipart text 字段、
 * `application/x-www-form-urlencoded` 解析后用本类装入。
 */
private class MapParameters(
    private val data: Map<String, List<String>>,
) : neton.core.http.Parameters {
    /** 从 Ktor 的 [io.ktor.http.Parameters] 直接构造，省一次手工转 Map。 */
    constructor(ktorParams: io.ktor.http.Parameters) : this(
        ktorParams.entries().associate { (k, v) -> k to v },
    )

    override fun get(name: String): String? = data[name]?.firstOrNull()
    override fun getAll(name: String): List<String> = data[name].orEmpty()
    override fun contains(name: String): Boolean = data.containsKey(name)
    override fun names(): Set<String> = data.keys
    override fun toMap(): Map<String, List<String>> = data
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
 * Ktor 请求头适配
 */
private class KtorRequestHeaders(private val call: io.ktor.server.application.ApplicationCall) :
    neton.core.http.Headers {
    override fun get(name: String): String? = call.request.headers[name]
    override fun getAll(name: String): List<String> = call.request.headers.getAll(name) ?: emptyList()
    override fun contains(name: String): Boolean = call.request.headers.contains(name)
    override fun names(): Set<String> = call.request.headers.names()
    override fun toMap(): Map<String, List<String>> {
        val map = mutableMapOf<String, List<String>>()
        for (name in call.request.headers.names()) {
            map[name] = call.request.headers.getAll(name) ?: emptyList()
        }
        return map
    }
}

/**
 * Ktor 查询参数适配
 */
private class KtorQueryParameters(private val call: io.ktor.server.application.ApplicationCall) :
    neton.core.http.Parameters {
    override fun get(name: String): String? = call.request.queryParameters[name]
    override fun getAll(name: String): List<String> = call.request.queryParameters.getAll(name) ?: emptyList()
    override fun contains(name: String): Boolean = call.request.queryParameters.contains(name)
    override fun names(): Set<String> = call.request.queryParameters.names()
    override fun toMap(): Map<String, List<String>> {
        val map = mutableMapOf<String, List<String>>()
        for (name in call.request.queryParameters.names()) {
            map[name] = call.request.queryParameters.getAll(name) ?: emptyList()
        }
        return map
    }
}

/**
 * 简化的 MutableHeaders 实现
 */
private class SimpleMutableHeaders : neton.core.http.MutableHeaders {
    private val map = LinkedHashMap<String, MutableList<String>>()
    private fun key(name: String) = name.lowercase()
    override fun get(name: String): String? = map[key(name)]?.firstOrNull()
    override fun getAll(name: String): List<String> = map[key(name)] ?: emptyList()
    override fun contains(name: String): Boolean = map.containsKey(key(name))
    override fun names(): Set<String> = map.keys
    override fun toMap(): Map<String, List<String>> = map
    override fun set(name: String, value: String) { map[key(name)] = mutableListOf(value) }
    override fun add(name: String, value: String) { map.getOrPut(key(name)) { mutableListOf() }.add(value) }
    override fun remove(name: String) { map.remove(key(name)) }
    override fun clear() { map.clear() }
}
