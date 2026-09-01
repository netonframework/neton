package neton.http.adapter

import neton.core.http.adapter.HttpServerConfig

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import neton.core.component.CorsConfig
import neton.core.component.NetonContext
import neton.core.http.ApiEnvelope
import neton.core.http.ArgsView
import neton.core.http.Cookie
import neton.core.http.HttpContext
import neton.core.http.HttpException
import neton.core.http.HttpMethod
import neton.core.http.HttpRequest
import neton.core.http.HttpResponse
import neton.core.http.HttpSession
import neton.core.http.HttpStatus
import neton.core.http.JsonContent
import neton.core.http.MemoryHttpSession
import neton.core.http.MutableHeaders
import neton.core.http.NetonErrorCode
import neton.core.http.Parameters
import neton.core.http.SimpleCookie
import neton.core.http.UploadFiles
import neton.core.http.ValidationException
import neton.core.http.httpStatusForErrorCode
import neton.core.interfaces.AccessLogEntry
import neton.core.interfaces.AccessLogWriter
import neton.core.interfaces.ConfiguredRouteGroups
import neton.core.interfaces.ErrorLogEntry
import neton.core.interfaces.ErrorLogWriter
import neton.core.interfaces.Identity
import neton.core.interfaces.RequestContext
import neton.core.interfaces.RequestEngine
import neton.core.interfaces.RateLimitGate
import neton.core.interfaces.RouteDefinition
import neton.core.interfaces.RouteGroupMounts
import neton.core.interfaces.RouteGroupSecurityConfigs
import neton.core.interfaces.SecurityAttributes
import neton.core.interfaces.SecurityConfiguration
import neton.logging.CurrentLogContext
import neton.logging.LogContext
import neton.logging.LoggerFactory
import neton.http.runRateLimitPreHandle
import neton.http.runSecurityPreHandle

/**
 * Immutable request snapshot owned by Kotlin. Adapters must copy borrowed engine buffers before
 * returning from their native callback.
 */
public class BufferedHttpRequest(
    public val method: String,
    public val path: String,
    public val query: String,
    public val headers: Map<String, List<String>>,
    public val body: ByteArray,
    /** transport 层对端地址（无 X-Forwarded-For 时用于 remoteAddress / access log userIp）。 */
    public val remoteAddress: String = "",
) {
    public fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
}

/**
 * Complete buffered response returned to the transport adapter.
 *
 * [streamed] 为 true 表示响应体已由 live transport 直接写给客户端（如 Ktor 真流式），
 * [body] 不含响应体字节，适配器不得再次写出。
 */
public class BufferedHttpResponse(
    public val status: Int,
    public val headers: Map<String, List<String>> = emptyMap(),
    public val body: ByteArray = ByteArray(0),
    public val streamed: Boolean = false,
)

/**
 * Standard dispatcher for buffered HTTP transports.
 *
 * Engine adapters own sockets and byte transfer only. This class owns Neton's routing, security,
 * rate limiting, CORS, response envelope, and in-memory HttpContext contract so every buffered
 * adapter has identical application behavior.
 */
public class BufferedHttpDispatcher(
    private val serverConfig: HttpServerConfig,
) {
    private var requestEngine: RequestEngine? = null
    private var rateLimitGate: RateLimitGate? = null
    private var appContext: NetonContext? = null
    private var securityInstalled: Boolean = false
    private var compiledRoutes: List<CompiledRoute> = emptyList()
    private var exactRoutes: Map<String, CompiledRoute> = emptyMap()
    private var logScope: CoroutineScope? = null

    public fun bind(ctx: NetonContext) {
        appContext = ctx
        requestEngine = ctx.get<RequestEngine>()
        rateLimitGate = ctx.getOrNull(RateLimitGate::class)
        securityInstalled = ctx.getOrNull(SecurityConfiguration::class) != null ||
            ctx.getOrNull(RouteGroupSecurityConfigs::class) != null
        compiledRoutes = buildCompiledRoutes(ctx)
        exactRoutes = compiledRoutes
            .filter { it.parameterSegments.isEmpty() }
            .associateBy { "${it.route.method.name} ${it.fullPattern}" }
        logScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    private fun buildCompiledRoutes(ctx: NetonContext): List<CompiledRoute> {
        val engine = requestEngine ?: return emptyList()
        val configuredGroups = ctx.getOrNull(ConfiguredRouteGroups::class)?.names.orEmpty()
        val mounts = ctx.getOrNull(RouteGroupMounts::class)?.groupToMount.orEmpty()
        return engine.getRoutes().map { route ->
            val group = route.routeGroup ?: inferRouteGroup(route.controllerClass, configuredGroups)
            val mount = group?.let { mounts[it] ?: "/$it" }.orEmpty()
            val fullPattern = joinPath(mount, route.pattern)
            val segments = fullPattern.split('/').filter(String::isNotEmpty)
            CompiledRoute(
                route = route,
                routeGroup = group,
                fullPattern = fullPattern,
                patternSegments = segments,
                parameterSegments = segments.filter { it.startsWith("{") && it.endsWith("}") },
            )
        }
    }

    public suspend fun dispatch(request: BufferedHttpRequest): BufferedHttpResponse =
        dispatch(request, liveResponse = null)

    /**
     * 统一请求入口：路由 + 安全 + 限流 + envelope，外加 LogContext / access log /
     * error log 可观测性。所有引擎（Hyper4k / Ktor）共用这一份实现。
     *
     * [liveResponse] 非 null 时作为 handler 看到的 `context.response`（真流式 transport，
     * 如 Ktor）；handler 提交后返回的 [BufferedHttpResponse.streamed] 为 true，适配器不再写出。
     * null 时 handler 写入内存缓冲，返回完整 [BufferedHttpResponse] 由 transport 写出。
     */
    public suspend fun dispatch(request: BufferedHttpRequest, liveResponse: HttpResponse?): BufferedHttpResponse {
        val startMs = kotlin.time.Clock.System.now().toEpochMilliseconds()
        val traceId = request.header("X-Request-Id")?.takeIf { it.isNotBlank() } ?: requestTraceId()
        CurrentLogContext.set(LogContext(traceId = traceId, requestId = traceId, spanId = null, userId = null))
        var status = 200
        var routePattern: String? = null
        var bytesOut = 0L
        var context: BufferedHttpContext? = null
        try {
            val outcome = if (request.method.equals("OPTIONS", ignoreCase = true) &&
                request.header("Access-Control-Request-Method") != null
            ) {
                DispatchOutcome(preflightResponse(request), null)
            } else {
                dispatchRoute(request, liveResponse, traceId)
            }
            context = outcome.context
            routePattern = outcome.routePattern
            status = outcome.response.status
            bytesOut = if (outcome.response.streamed) liveResponse?.bytesOut ?: 0L else outcome.response.body.size.toLong()
            return applyCors(request, outcome.response)
        } finally {
            val endMs = kotlin.time.Clock.System.now().toEpochMilliseconds()
            recordDispatch(request, context, traceId, routePattern, status, startMs, endMs, bytesOut)
            CurrentLogContext.clear()
        }
    }

    private data class DispatchOutcome(
        val response: BufferedHttpResponse,
        val routePattern: String?,
        val context: BufferedHttpContext? = null,
    )

    private suspend fun dispatchRoute(
        request: BufferedHttpRequest,
        liveResponse: HttpResponse?,
        traceId: String,
    ): DispatchOutcome {
        val engine = requestEngine ?: return DispatchOutcome(
            errorResponse(
                HttpStatus.SERVICE_UNAVAILABLE,
                NetonErrorCode.SERVICE_UNAVAILABLE,
                "RequestEngine is not available",
            ),
            null,
        )
        val method = request.method.toHttpMethod() ?: return DispatchOutcome(
            errorResponse(
                HttpStatus.METHOD_NOT_ALLOWED,
                NetonErrorCode.OPERATION_NOT_ALLOWED,
                "Unsupported HTTP method: ${request.method}",
            ),
            null,
        )
        val matched = lookupRoute(method, request.path) ?: return DispatchOutcome(
            errorResponse(
                HttpStatus.NOT_FOUND,
                NetonErrorCode.RESOURCE_NOT_FOUND,
                "Route not found: ${request.method} ${request.path}",
            ),
            null,
        )

        val context = BufferedHttpContext(request, method, matched.pathParameters, appContext, traceId, liveResponse)
        return try {
            runSecurity(matched, context, request)
            val allowed = runRateLimitPreHandle(matched.route, context, rateLimitGate)
            if (!allowed) {
                // gate 已把 429（含 X-RateLimit-* / Retry-After）写入 response，直接快照返回。
                return DispatchOutcome(snapshotResponse(context), matched.route.pattern, context)
            }

            val result = matched.route.handler.invoke(
                context,
                ArgsView(matched.pathParameters, context.request.queryParams.toMap()),
            )
            val response = if (context.response.isCommitted) {
                snapshotResponse(context)
            } else {
                successResponse(result)
            }
            DispatchOutcome(response, matched.route.pattern, context)
        } catch (e: ValidationException) {
            // ValidationException 默认走 InvalidParams（spec ERROR_CODE_SPEC §3）；
            // 业务侧需要更细错误码请直接 throw HttpException(code, ...)
            DispatchOutcome(
                errorResponse(HttpStatus.BAD_REQUEST, NetonErrorCode.INVALID_PARAMS, e.message),
                matched.route.pattern,
                context,
            )
        } catch (e: HttpException) {
            // body.code 是权威，HTTP status 由 framework 内部 [httpStatusForErrorCode] 推导。
            val httpStatus = httpStatusForErrorCode(e.code)
            // 4xx 是**客户端错误**，不是故障：token 过期、参数不合法、没权限，都在预期之内，
            // 一行结构化日志（method/path/status/traceId）就够定位了。带上 cause 会打出完整
            // Kotlin 堆栈——生产上实测每条约 47 行，56624 次异常把 error.log 撑到 434MB，
            // 其中 219 万行是堆栈，真正的故障反而被埋了。5xx 仍然带堆栈。
            val isClientError = httpStatus.code in 400..499
            logger()?.warn(
                "http.error",
                fields = mapOf(
                    "method" to request.method,
                    "path" to request.path,
                    "status" to httpStatus.code,
                    "traceId" to traceId,
                ),
                cause = if (isClientError) null else e,
            )
            DispatchOutcome(
                errorResponse(httpStatus, e.code, e.message),
                matched.route.pattern,
                context,
            )
        } catch (e: CancellationException) {
            // 客户端断连/协程取消：不是业务错误，向上冒泡由引擎收尾（流式响应已提交时不可再写）
            throw e
        } catch (e: Exception) {
            if (context.response.isCommitted) {
                // 响应已提交（典型：流式写出中客户端断连 Broken pipe）——无法再回 envelope，按 WARN 收尾
                logger()?.warn(
                    "http.stream.aborted",
                    fields = mapOf("method" to request.method, "path" to request.path, "traceId" to traceId),
                    cause = e,
                )
                return DispatchOutcome(snapshotResponse(context), matched.route.pattern, context)
            }
            logger()?.error(
                "http.error",
                fields = mapOf(
                    "method" to request.method,
                    "path" to request.path,
                    "status" to HttpStatus.INTERNAL_SERVER_ERROR.code,
                    "traceId" to traceId,
                    "route" to "${matched.route.method.name} ${matched.route.pattern} -> " +
                        "${matched.route.controllerClass}.${matched.route.methodName}",
                ),
                cause = e,
            )
            writeErrorLog(request, context, e)
            // 兜底未捕获异常 → InternalError（spec ERROR_CODE_SPEC system 段），HTTP 仍 500
            DispatchOutcome(
                errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, NetonErrorCode.INTERNAL_ERROR, "Internal Server Error"),
                matched.route.pattern,
                context,
            )
        }
    }

    /** live transport 已提交 → 空体快照（[BufferedHttpResponse.streamed]=true）；内存缓冲 → 完整拷贝。 */
    private fun snapshotResponse(context: BufferedHttpContext): BufferedHttpResponse {
        val response = context.response
        val buffered = response as? BufferedMemoryResponse
            ?: return BufferedHttpResponse(
                status = response.status.code,
                headers = response.headers.toMap(),
                body = ByteArray(0),
                streamed = true,
            )
        return BufferedHttpResponse(
            status = buffered.status.code,
            headers = buffered.headers.toMap(),
            body = buffered.body ?: ByteArray(0),
        )
    }

    private fun BufferedHttpRequest.logUserIp(): String =
        header("X-Forwarded-For")?.substringBefore(',')?.trim()?.takeIf { it.isNotEmpty() } ?: remoteAddress

    /**
     * access log（msg=http.access）+ 异步落库（[AccessLogWriter]），所有引擎共用。
     * 在 dispatch 的 finally 中调用：异常路径（含 CancellationException 重抛）也要留下访问记录。
     */
    private fun recordDispatch(
        request: BufferedHttpRequest,
        context: BufferedHttpContext?,
        traceId: String,
        routePattern: String?,
        status: Int,
        startMs: Long,
        endMs: Long,
        bytesOut: Long,
    ) {
        val log = logger()
        val method = request.method
        val path = request.path
        log?.info(
            "http.access",
            mapOf(
                "method" to method,
                "path" to path,
                "routePattern" to (routePattern ?: "-"),
                "status" to status,
                "latencyMs" to (endMs - startMs),
                "bytesIn" to request.body.size.toLong(),
                "bytesOut" to bytesOut,
                "traceId" to traceId,
            ),
        )
        val accessLogWriter = appContext?.getOrNull(AccessLogWriter::class) ?: return
        val identity = context?.attributes?.get("identity") as? Identity
        val entry = AccessLogEntry(
            userId = identity?.id?.toLongOrNull(),
            userType = if (identity != null) 2 else 0,
            applicationName = "neton-application",
            requestMethod = method,
            requestUrl = path,
            requestParams = request.query.takeIf { it.isNotEmpty() },
            userIp = request.logUserIp(),
            userAgent = request.header("User-Agent"),
            beginTime = startMs,
            endTime = endMs,
            duration = endMs - startMs,
            resultCode = status,
            resultMsg = null,
        )
        logScope?.launch {
            try {
                accessLogWriter.write(entry)
            } catch (e: Exception) {
                log?.warn("access-log.write.failed", mapOf("path" to path), cause = e)
            }
        }
    }

    /** 5xx 未捕获异常异步落库（[ErrorLogWriter]），与旧 Ktor 适配器行为一致。 */
    private fun writeErrorLog(request: BufferedHttpRequest, context: BufferedHttpContext?, e: Exception) {
        val errorLogWriter = appContext?.getOrNull(ErrorLogWriter::class) ?: return
        val identity = context?.attributes?.get("identity") as? Identity
        val entry = ErrorLogEntry(
            userId = identity?.id?.toLongOrNull(),
            userType = if (identity != null) 2 else 0,
            applicationName = "neton-application",
            requestMethod = request.method,
            requestUrl = request.path,
            requestParams = request.query.takeIf { it.isNotEmpty() },
            userIp = request.logUserIp(),
            userAgent = request.header("User-Agent"),
            exceptionName = e::class.simpleName ?: "Exception",
            exceptionMessage = e.message,
            exceptionStackTrace = e.stackTraceToString(),
        )
        logScope?.launch {
            try {
                errorLogWriter.write(entry)
            } catch (writeEx: Exception) {
                logger()?.warn("error-log.write.failed", mapOf("path" to request.path), cause = writeEx)
            }
        }
    }

    private fun preflightResponse(request: BufferedHttpRequest): BufferedHttpResponse {
        val cors = corsConfig() ?: return errorResponse(
            HttpStatus.FORBIDDEN,
            NetonErrorCode.PERMISSION_DENIED,
            "CORS is not enabled",
        )
        val origin = request.header("Origin") ?: return errorResponse(
            HttpStatus.BAD_REQUEST,
            NetonErrorCode.INVALID_PARAMS,
            "Origin header is required",
        )
        if (!cors.allowsOrigin(origin)) {
            return errorResponse(HttpStatus.FORBIDDEN, NetonErrorCode.PERMISSION_DENIED, "Origin is not allowed")
        }
        return BufferedHttpResponse(
            status = HttpStatus.NO_CONTENT.code,
            headers = mapOf(
                "Access-Control-Allow-Methods" to listOf(cors.allowedMethods.joinToString(", ")),
                "Access-Control-Allow-Headers" to listOf(cors.allowedHeaders.joinToString(", ")),
                "Access-Control-Max-Age" to listOf(cors.maxAgeSeconds.toString()),
            ),
        )
    }

    private fun applyCors(request: BufferedHttpRequest, response: BufferedHttpResponse): BufferedHttpResponse {
        val extra = corsHeaders(request)
        if (extra.isEmpty()) return response
        val headers = response.headers.toMutableMap().apply { putAll(extra) }
        // streamed 响应的 body 已由 live transport 写出；这里仅能补 CORS 头（未提交时由适配器写回）
        return BufferedHttpResponse(response.status, headers, response.body, response.streamed)
    }

    /**
     * 当前请求应追加的 CORS 响应头（含 Vary）；未命中返回空。
     * 供 live transport 在提交响应**前**注入（提交后头无法再补），buffered 路径由 [applyCors] 内部使用。
     */
    public fun corsHeaders(request: BufferedHttpRequest): Map<String, List<String>> {
        val cors = corsConfig() ?: return emptyMap()
        val origin = request.header("Origin") ?: return emptyMap()
        if (!cors.allowsOrigin(origin)) return emptyMap()
        val allowOrigin = if ("*" in cors.allowedOrigins && !cors.allowCredentials) "*" else origin
        return buildMap {
            put("Access-Control-Allow-Origin", listOf(allowOrigin))
            put("Vary", listOf("Origin"))
            if (cors.allowCredentials) put("Access-Control-Allow-Credentials", listOf("true"))
        }
    }

    private suspend fun runSecurity(
        matched: MatchedRoute,
        context: BufferedHttpContext,
        request: BufferedHttpRequest,
    ) {
        val route = matched.route
        if (!securityInstalled && !route.requireAuth && route.permission == null) {
            context.removeAttribute(SecurityAttributes.IDENTITY)
            return
        }
        val headers = request.headers.mapValues { it.value.firstOrNull().orEmpty() }
        val requestContext = object : RequestContext {
            override val path: String = request.path
            override val method: String = request.method
            override val headers: Map<String, String> = headers
            override val routeGroup: String? = matched.routeGroup
        }
        runSecurityPreHandle(
            route = matched.route,
            httpContext = context,
            requestContext = requestContext,
            securityConfig = appContext?.getOrNull(SecurityConfiguration::class),
            routeGroupSecurityConfigs = appContext?.getOrNull(RouteGroupSecurityConfigs::class),
        )
    }

    private fun lookupRoute(method: HttpMethod, path: String): MatchedRoute? {
        if (isCanonicalPath(path)) {
            exactRoutes["${method.name} $path"]?.let { return MatchedRoute(it.route, it.routeGroup, emptyMap()) }
        }
        return matchRoute(method, path)
    }

    /** True when [path] already equals its [normalizePath] form, so the exact index lookup is valid. */
    private fun isCanonicalPath(path: String): Boolean {
        if (path == "/") return true
        if (path.length < 2 || path[0] != '/' || path[path.length - 1] == '/') return false
        var index = 1
        while (index < path.length) {
            if (path[index] == '/' && path[index - 1] == '/') return false
            index++
        }
        return true
    }

    private fun matchRoute(method: HttpMethod, path: String): MatchedRoute? {
        val segments = normalizePath(path).split('/').filter(String::isNotEmpty)
        for (compiled in compiledRoutes) {
            if (compiled.route.method != method) continue
            val patternSegments = compiled.patternSegments
            if (patternSegments.size != segments.size) continue
            if (compiled.parameterSegments.isEmpty()) {
                var matchedAll = true
                for (index in patternSegments.indices) {
                    if (patternSegments[index] != segments[index]) {
                        matchedAll = false
                        break
                    }
                }
                if (matchedAll) return MatchedRoute(compiled.route, compiled.routeGroup, emptyMap())
            } else {
                val params = HashMap<String, String>(compiled.parameterSegments.size)
                var matchedAll = true
                for (index in patternSegments.indices) {
                    val expected = patternSegments[index]
                    val actual = segments[index]
                    if (expected.startsWith("{") && expected.endsWith("}")) {
                        params[expected.substring(1, expected.lastIndex)] = percentDecode(actual)
                    } else if (expected != actual) {
                        matchedAll = false
                        break
                    }
                }
                if (matchedAll) return MatchedRoute(compiled.route, compiled.routeGroup, params)
            }
        }
        return null
    }

    private fun successResponse(result: Any?): BufferedHttpResponse {
        if (result !is JsonContent) {
            fastEnvelopeBody(NetonErrorCode.OK, "OK", result)?.let { body ->
                return BufferedHttpResponse(status = HttpStatus.OK.code, headers = jsonHeaders, body = body)
            }
        }
        val data = when (result) {
            null, is Unit -> JsonNull
            is JsonContent -> envelopeJson.parseToJsonElement(result.json)
            else -> valueToJsonElement(result)
        }
        return envelopeResponse(HttpStatus.OK, ApiEnvelope.ok(data))
    }

    private fun errorResponse(status: HttpStatus, code: Int, message: String): BufferedHttpResponse =
        envelopeResponse(status, ApiEnvelope.error(code, message))

    public fun transportFailureResponse(status: Int, message: String): BufferedHttpResponse = when (status) {
        HttpStatus.GATEWAY_TIMEOUT.code -> errorResponse(HttpStatus.GATEWAY_TIMEOUT, NetonErrorCode.TIMEOUT, message)
        HttpStatus.SERVICE_UNAVAILABLE.code ->
            errorResponse(HttpStatus.SERVICE_UNAVAILABLE, NetonErrorCode.SERVICE_UNAVAILABLE, message)
        else -> errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, NetonErrorCode.INTERNAL_ERROR, message)
    }

    private val jsonHeaders: Map<String, List<String>> =
        mapOf("Content-Type" to listOf("application/json; charset=utf-8"))

    private fun envelopeResponse(status: HttpStatus, envelope: ApiEnvelope): BufferedHttpResponse = BufferedHttpResponse(
        status = status.code,
        headers = jsonHeaders,
        body = envelopeJson.encodeToString(ApiEnvelope.serializer(), envelope).encodeToByteArray(),
    )

    private fun logger() = appContext?.getOrNull(LoggerFactory::class)?.get("neton.http")

    /** CORS is policy, not transport: it arrives via the context like the security config does. */
    private fun corsConfig(): CorsConfig? = appContext?.getOrNull(CorsConfig::class)

    private data class MatchedRoute(
        val route: RouteDefinition,
        val routeGroup: String?,
        val pathParameters: Map<String, String>,
    )

    private data class CompiledRoute(
        val route: RouteDefinition,
        val routeGroup: String?,
        val fullPattern: String,
        val patternSegments: List<String>,
        val parameterSegments: List<String>,
    )

    public companion object {
        val envelopeJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = true
        }

        fun requestTraceId(): String =
            "req-${kotlin.time.Clock.System.now().toEpochMilliseconds()}-${(0 until 100000).random()}"

        fun String.toHttpMethod(): HttpMethod? =
            try {
                HttpMethod.valueOf(uppercase())
            } catch (_: IllegalArgumentException) {
                null
            }

        fun inferRouteGroup(controllerClass: String?, configuredGroups: Set<String>): String? {
            val segments = controllerClass?.split(".") ?: return null
            return segments.dropLast(1).firstOrNull { it in configuredGroups }
        }

        internal fun joinPath(mount: String, route: String): String {
            val joined = listOf(mount, route)
                .flatMap { it.split('/').filter(String::isNotEmpty) }
                .joinToString("/", prefix = "/")
            return normalizePath(joined)
        }

        fun normalizePath(path: String): String {
            if (path.isBlank() || path == "/") return "/"
            return "/" + path.substringBefore('?').split('/').filter(String::isNotEmpty).joinToString("/")
        }

        fun matchPath(pattern: String, path: String): Map<String, String>? {
            val patternSegments = normalizePath(pattern).split('/').filter(String::isNotEmpty)
            val pathSegments = normalizePath(path).split('/').filter(String::isNotEmpty)
            if (patternSegments.size != pathSegments.size) return null

            val params = mutableMapOf<String, String>()
            for (index in patternSegments.indices) {
                val expected = patternSegments[index]
                val actual = pathSegments[index]
                if (expected.startsWith("{") && expected.endsWith("}")) {
                    params[expected.substring(1, expected.lastIndex)] = percentDecode(actual)
                } else if (expected != actual) {
                    return null
                }
            }
            return params
        }

        /**
         * Byte-exact fast encoder for the response envelope over common value shapes.
         *
         * Returns null when the value needs the general kotlinx.serialization path
         * (JsonElement payloads, exotic types, non-finite numbers); callers fall back.
         */
        internal fun fastEnvelopeBody(code: Int, message: String, value: Any?): ByteArray? {
            val sb = StringBuilder(64)
            sb.append("{\"code\":").append(code).append(",\"message\":")
            if (!appendJsonString(sb, message)) return null
            sb.append(",\"data\":")
            if (!appendJsonValue(sb, value)) return null
            sb.append('}')
            return sb.toString().encodeToByteArray()
        }

        private fun appendJsonValue(sb: StringBuilder, value: Any?): Boolean = when (value) {
            null, is Unit -> {
                sb.append("null")
                true
            }
            is JsonElement -> false
            is String -> appendJsonString(sb, value)
            is Boolean, is Byte, is Short, is Int, is Long -> {
                sb.append(value.toString())
                true
            }
            is Float, is Double -> {
                val number = (value as Number).toDouble()
                if (number.isNaN() || number.isInfinite()) {
                    false
                } else {
                    sb.append(value.toString())
                    true
                }
            }
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((key, item) in value) {
                    if (!first) sb.append(',')
                    first = false
                    if (!appendJsonString(sb, key.toString())) return false
                    sb.append(':')
                    if (!appendJsonValue(sb, item)) return false
                }
                sb.append('}')
                true
            }
            is Iterable<*> -> appendJsonArray(sb, value.iterator())
            is Array<*> -> appendJsonArray(sb, value.iterator())
            else -> false
        }

        private fun appendJsonArray(sb: StringBuilder, values: Iterator<*>): Boolean {
            sb.append('[')
            var first = true
            for (item in values) {
                if (!first) sb.append(',')
                first = false
                if (!appendJsonValue(sb, item)) return false
            }
            sb.append(']')
            return true
        }

        private fun appendJsonString(sb: StringBuilder, value: String): Boolean {
            sb.append('"')
            for (char in value) {
                when {
                    char == '"' -> sb.append("\\\"")
                    char == '\\' -> sb.append("\\\\")
                    char == '\n' -> sb.append("\\n")
                    char == '\r' -> sb.append("\\r")
                    char == '\t' -> sb.append("\\t")
                    char == '\b' -> sb.append("\\b")
                    char == '\u000C' -> sb.append("\\f")
                    char.code < 0x20 -> sb.append("\\u").append(char.code.toString(16).padStart(4, '0'))
                    else -> sb.append(char)
                }
            }
            sb.append('"')
            return true
        }

        fun valueToJsonElement(value: Any?): JsonElement = when (value) {
            null -> JsonNull
            is JsonElement -> value
            is String -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is Map<*, *> -> buildJsonObject {
                value.forEach { (key, item) -> put(key.toString(), valueToJsonElement(item)) }
            }
            is Iterable<*> -> buildJsonArray { value.forEach { add(valueToJsonElement(it)) } }
            is Array<*> -> buildJsonArray { value.forEach { add(valueToJsonElement(it)) } }
            else -> JsonPrimitive(value.toString())
        }

        fun parseParameters(raw: String): Map<String, List<String>> {
            if (raw.isBlank()) return emptyMap()
            val result = linkedMapOf<String, MutableList<String>>()
            raw.split('&').forEach { pair ->
                if (pair.isEmpty()) return@forEach
                val separator = pair.indexOf('=')
                val key = percentDecode(if (separator < 0) pair else pair.substring(0, separator))
                val value = percentDecode(if (separator < 0) "" else pair.substring(separator + 1))
                result.getOrPut(key) { mutableListOf() }.add(value)
            }
            return result
        }

        fun percentDecode(value: String): String {
            val bytes = ArrayList<Byte>(value.length)
            var index = 0
            while (index < value.length) {
                when {
                    value[index] == '+' -> {
                        bytes.add(' '.code.toByte())
                        index++
                    }
                    value[index] == '%' && index + 2 < value.length -> {
                        val decoded = value.substring(index + 1, index + 3).toIntOrNull(16)
                        if (decoded != null) {
                            bytes.add(decoded.toByte())
                            index += 3
                        } else {
                            bytes.add(value[index].code.toByte())
                            index++
                        }
                    }
                    else -> {
                        value[index].toString().encodeToByteArray().forEach(bytes::add)
                        index++
                    }
                }
            }
            return bytes.toByteArray().decodeToString()
        }
    }
}

private fun neton.core.component.CorsConfig.allowsOrigin(origin: String): Boolean =
    "*" in allowedOrigins || allowedOrigins.any { it.equals(origin, ignoreCase = true) }

private class BufferedHttpContext(
    request: BufferedHttpRequest,
    method: HttpMethod,
    pathParameters: Map<String, String>,
    private val appContext: NetonContext?,
    override val traceId: String,
    liveResponse: HttpResponse? = null,
) : HttpContext {
    override val request: HttpRequest by lazy(LazyThreadSafetyMode.PUBLICATION) {
        BufferedHttpRequestView(request, method, pathParameters)
    }

    /** 无 live transport 时的内存缓冲响应；有 live transport 时仅为兼容占位，实际写 [response]。 */
    internal val bufferedResponse: BufferedMemoryResponse? = if (liveResponse == null) BufferedMemoryResponse() else null
    override val response: HttpResponse = liveResponse ?: bufferedResponse!!
    override val session: HttpSession by lazy(LazyThreadSafetyMode.PUBLICATION) {
        MemoryHttpSession("buffered-http-$traceId")
    }
    override val attributes: MutableMap<String, Any> = mutableMapOf()

    override fun getApplicationContext(): NetonContext? = appContext
}

private class BufferedHttpRequestView(
    private val source: BufferedHttpRequest,
    override val method: HttpMethod,
    pathParameters: Map<String, String>,
) : HttpRequest {
    override val path: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
        BufferedHttpDispatcher.normalizePath(source.path)
    }
    override val url: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
        if (source.query.isEmpty()) path else "$path?${source.query}"
    }
    override val version: String = "HTTP/1.1"
    override val headers: neton.core.http.Headers by lazy(LazyThreadSafetyMode.PUBLICATION) {
        MapHeaders(source.headers)
    }
    override val queryParams: Parameters by lazy(LazyThreadSafetyMode.PUBLICATION) {
        BufferedParameters(BufferedHttpDispatcher.parseParameters(source.query))
    }
    override val pathParams: Parameters = BufferedParameters(pathParameters.mapValues { listOf(it.value) })
    override val cookies: Map<String, Cookie> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        parseCookies(source.header("Cookie"))
    }
    override val remoteAddress: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
        source.header("X-Forwarded-For")?.substringBefore(',')?.trim()?.takeIf { it.isNotEmpty() }
            ?: source.remoteAddress
    }
    override val isSecure: Boolean by lazy(LazyThreadSafetyMode.PUBLICATION) {
        source.header("X-Forwarded-Proto")?.equals("https", ignoreCase = true) == true
    }

    override suspend fun body(): ByteArray = source.body.copyOf()
    override suspend fun text(): String = source.body.decodeToString()
    override suspend fun json(): Any = Json.parseToJsonElement(text())

    /** multipart 解析结果缓存：同一请求内 uploadFiles() / form() 复用同一份字节解析。 */
    private var parsedMultipart: ParsedMultipart? = null

    private fun ensureMultipartParsed(): ParsedMultipart? {
        parsedMultipart?.let { return it }
        val ct = contentType ?: return null
        if (!ct.substringBefore(';').trim().equals("multipart/form-data", ignoreCase = true)) return null
        val parsed = MultipartFormParser.parse(source.body, ct) ?: return null
        parsedMultipart = parsed
        return parsed
    }

    override suspend fun form(): Parameters {
        val ct = contentType?.substringBefore(';')?.trim()
        return when {
            ct.equals("application/x-www-form-urlencoded", ignoreCase = true) ->
                BufferedParameters(BufferedHttpDispatcher.parseParameters(text()))
            ct.equals("multipart/form-data", ignoreCase = true) ->
                ensureMultipartParsed()?.asParameters() ?: BufferedParameters(emptyMap())
            else -> BufferedParameters(emptyMap())
        }
    }

    override suspend fun uploadFiles(): UploadFiles =
        ensureMultipartParsed()?.asUploadFiles() ?: UploadFiles(emptyList())

    private fun parseCookies(header: String?): Map<String, Cookie> {
        if (header.isNullOrBlank()) return emptyMap()
        return header.split(';').mapNotNull { item ->
            val separator = item.indexOf('=')
            if (separator <= 0) return@mapNotNull null
            val name = item.substring(0, separator).trim()
            name to SimpleCookie(name, item.substring(separator + 1).trim())
        }.toMap()
    }
}

private class BufferedMemoryResponse : HttpResponse {
    override var status: HttpStatus = HttpStatus.OK
    override val headers: MutableHeaders = MapMutableHeaders()
    override val isCommitted: Boolean get() = body != null
    internal var body: ByteArray? = null
        private set
    override val bytesOut: Long get() = (body?.size ?: 0).toLong()

    override fun cookie(cookie: Cookie) {
        val value = buildString {
            append(cookie.name).append('=').append(cookie.value)
            cookie.path?.let { append("; Path=").append(it) }
            cookie.domain?.let { append("; Domain=").append(it) }
            cookie.maxAge?.let { append("; Max-Age=").append(it) }
            if (cookie.secure) append("; Secure")
            if (cookie.httpOnly) append("; HttpOnly")
            cookie.sameSite?.let { append("; SameSite=").append(it.name.lowercase().replaceFirstChar(Char::uppercase)) }
        }
        headers.add("Set-Cookie", value)
    }

    override suspend fun write(data: ByteArray) {
        check(!isCommitted) { "Response already committed" }
        body = data.copyOf()
    }

    override suspend fun redirect(url: String, status: HttpStatus) {
        check(!isCommitted) { "Response already committed" }
        this.status = status
        headers["Location"] = url
        body = ByteArray(0)
    }
}

private open class MapHeaders(initialValues: Map<String, List<String>>) : neton.core.http.Headers {
    protected val values: MutableMap<String, MutableList<String>> =
        initialValues.mapValuesTo(linkedMapOf()) { it.value.toMutableList() }

    protected fun actualName(name: String): String? = values.keys.firstOrNull { it.equals(name, true) }
    override fun get(name: String): String? = actualName(name)?.let { values[it]?.firstOrNull() }
    override fun getAll(name: String): List<String> = actualName(name)?.let { values[it].orEmpty() }.orEmpty()
    override fun contains(name: String): Boolean = actualName(name) != null
    override fun names(): Set<String> = values.keys
    override fun toMap(): Map<String, List<String>> = values.mapValues { it.value.toList() }
}

private class MapMutableHeaders : MapHeaders(emptyMap()), MutableHeaders {
    override fun set(name: String, value: String) {
        actualName(name)?.let(values::remove)
        values[name] = mutableListOf(value)
    }

    override fun add(name: String, value: String) {
        val key = actualName(name) ?: name
        values.getOrPut(key) { mutableListOf() }.add(value)
    }

    override fun remove(name: String) {
        actualName(name)?.let(values::remove)
    }

    override fun clear() = values.clear()
}

private class BufferedParameters(
    private val values: Map<String, List<String>>,
) : Parameters {
    override fun get(name: String): String? = values[name]?.firstOrNull()
    override fun getAll(name: String): List<String> = values[name].orEmpty()
    override fun contains(name: String): Boolean = name in values
    override fun names(): Set<String> = values.keys
    override fun toMap(): Map<String, List<String>> = values
}
