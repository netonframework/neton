package neton.http.hyper4k

import hyper4k.Hyper4kRequest
import hyper4k.Hyper4kResponse
import hyper4k.Hyper4kServer
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
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
import neton.core.http.adapter.HttpAdapter
import neton.core.http.httpStatusForErrorCode
import neton.core.interfaces.ConfiguredRouteGroups
import neton.core.interfaces.RequestContext
import neton.core.interfaces.RequestEngine
import neton.core.interfaces.RouteDefinition
import neton.core.interfaces.RouteGroupMounts
import neton.core.interfaces.RouteGroupSecurityConfigs
import neton.core.interfaces.SecurityConfiguration
import neton.logging.CurrentLogContext
import neton.logging.LogContext
import neton.logging.LoggerFactory
import neton.http.HttpServerConfig
import neton.http.runSecurityPreHandle

/** Tokio + Hyper transport adapter for Neton on Kotlin/Native. */
class HyperHttpAdapter(
    private val serverConfig: HttpServerConfig,
) : HttpAdapter {
    private var server: Hyper4kServer? = null
    private var requestEngine: RequestEngine? = null
    private var appContext: NetonContext? = null

    override fun port(): Int = serverConfig.port

    override fun adapterName(): String = "hyper4k"

    override suspend fun start(ctx: NetonContext, onStarted: ((coldStartMs: Long) -> Unit)?) {
        check(server == null) { "hyper4k server already started" }
        bindContext(ctx)

        val startedAt = kotlin.time.Clock.System.now().toEpochMilliseconds()
        val runningServer = Hyper4kServer(host = "0.0.0.0", port = serverConfig.port)
        runningServer.start { request -> runBlocking { dispatch(request) } }
        server = runningServer

        val coldStartMs = kotlin.time.Clock.System.now().toEpochMilliseconds() - startedAt
        logger()?.info("neton.http.hyper4k.started", mapOf("port" to serverConfig.port))
        onStarted?.invoke(coldStartMs)

        while (server != null) delay(250)
    }

    override suspend fun stop() {
        val runningServer = server ?: return
        server = null
        runningServer.stop()
    }

    internal fun bindContext(ctx: NetonContext) {
        appContext = ctx
        requestEngine = ctx.get<RequestEngine>()
    }

    internal suspend fun dispatch(request: Hyper4kRequest): Hyper4kResponse {
        val response = if (request.method.equals("OPTIONS", ignoreCase = true) &&
            request.header("Access-Control-Request-Method") != null
        ) {
            preflightResponse(request)
        } else {
            dispatchRoute(request)
        }
        return applyCors(request, response)
    }

    private suspend fun dispatchRoute(request: Hyper4kRequest): Hyper4kResponse {
        val engine = requestEngine ?: return errorResponse(
            HttpStatus.SERVICE_UNAVAILABLE,
            NetonErrorCode.SERVICE_UNAVAILABLE,
            "RequestEngine is not available",
        )
        val method = request.method.toHttpMethod() ?: return errorResponse(
            HttpStatus.METHOD_NOT_ALLOWED,
            NetonErrorCode.OPERATION_NOT_ALLOWED,
            "Unsupported HTTP method: ${request.method}",
        )
        val matched = matchRoute(engine.getRoutes(), method, request.path) ?: return errorResponse(
            HttpStatus.NOT_FOUND,
            NetonErrorCode.RESOURCE_NOT_FOUND,
            "Route not found: ${request.method} ${request.path}",
        )

        val traceId = request.header("X-Request-Id")?.takeIf { it.isNotBlank() } ?: requestTraceId()
        val context = HyperHttpContext(request, method, matched.pathParameters, appContext, traceId)
        val logContext = LogContext(traceId = traceId, requestId = traceId, spanId = null, userId = null)
        CurrentLogContext.set(logContext)

        return try {
            runSecurity(matched, context, request)
            val result = matched.route.handler.invoke(
                context,
                ArgsView(matched.pathParameters, context.request.queryParams.toMap()),
            )
            if (context.hyperResponse.isCommitted) {
                context.hyperResponse.toHyper4kResponse()
            } else {
                successResponse(result)
            }
        } catch (e: ValidationException) {
            errorResponse(HttpStatus.BAD_REQUEST, NetonErrorCode.INVALID_PARAMS, e.message)
        } catch (e: HttpException) {
            errorResponse(httpStatusForErrorCode(e.code), e.code, e.message)
        } catch (e: Exception) {
            logger()?.error(
                "http.error",
                mapOf("method" to request.method, "path" to request.path, "traceId" to traceId),
                cause = e,
            )
            errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, NetonErrorCode.INTERNAL_ERROR, "Internal Server Error")
        } finally {
            CurrentLogContext.clear()
        }
    }

    private fun preflightResponse(request: Hyper4kRequest): Hyper4kResponse {
        val cors = serverConfig.corsConfig ?: return errorResponse(
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
        return Hyper4kResponse(
            status = HttpStatus.NO_CONTENT.code,
            headers = mapOf(
                "Access-Control-Allow-Methods" to cors.allowedMethods.joinToString(", "),
                "Access-Control-Allow-Headers" to cors.allowedHeaders.joinToString(", "),
                "Access-Control-Max-Age" to cors.maxAgeSeconds.toString(),
            ),
        )
    }

    private fun applyCors(request: Hyper4kRequest, response: Hyper4kResponse): Hyper4kResponse {
        val cors = serverConfig.corsConfig ?: return response
        val origin = request.header("Origin") ?: return response
        if (!cors.allowsOrigin(origin)) return response

        val allowOrigin = if ("*" in cors.allowedOrigins && !cors.allowCredentials) "*" else origin
        val headers = response.headers.toMutableMap().apply {
            put("Access-Control-Allow-Origin", allowOrigin)
            put("Vary", "Origin")
            if (cors.allowCredentials) put("Access-Control-Allow-Credentials", "true")
        }
        return Hyper4kResponse(response.status, headers, response.body)
    }

    private suspend fun runSecurity(
        matched: MatchedRoute,
        context: HyperHttpContext,
        request: Hyper4kRequest,
    ) {
        val headers = request.headers.entries.associate { it.key to it.value }
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

    private fun matchRoute(routes: List<RouteDefinition>, method: HttpMethod, path: String): MatchedRoute? {
        val configuredGroups = appContext?.getOrNull(ConfiguredRouteGroups::class)?.names.orEmpty()
        val mounts = appContext?.getOrNull(RouteGroupMounts::class)?.groupToMount.orEmpty()
        val normalizedPath = normalizePath(path)

        for (route in routes.filter { it.method == method }) {
            val group = route.routeGroup ?: inferRouteGroup(route.controllerClass, configuredGroups)
            val mount = group?.let { mounts[it] ?: "/$it" }.orEmpty()
            val fullPattern = joinPath(mount, route.pattern)
            val params = matchPath(fullPattern, normalizedPath) ?: continue
            return MatchedRoute(route, group, params)
        }
        return null
    }

    private fun successResponse(result: Any?): Hyper4kResponse {
        val data = when (result) {
            null, is Unit -> JsonNull
            is JsonContent -> envelopeJson.parseToJsonElement(result.json)
            else -> valueToJsonElement(result)
        }
        return envelopeResponse(HttpStatus.OK, ApiEnvelope.ok(data))
    }

    private fun errorResponse(status: HttpStatus, code: Int, message: String): Hyper4kResponse =
        envelopeResponse(status, ApiEnvelope.error(code, message))

    private fun envelopeResponse(status: HttpStatus, envelope: ApiEnvelope): Hyper4kResponse = Hyper4kResponse(
        status = status.code,
        headers = mapOf("Content-Type" to "application/json; charset=utf-8"),
        body = envelopeJson.encodeToString(ApiEnvelope.serializer(), envelope).encodeToByteArray(),
    )

    private fun logger() = appContext?.getOrNull(LoggerFactory::class)?.get("neton.http")

    private data class MatchedRoute(
        val route: RouteDefinition,
        val routeGroup: String?,
        val pathParameters: Map<String, String>,
    )

    internal companion object {
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

        fun joinPath(mount: String, route: String): String {
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

internal class HyperHttpContext(
    request: Hyper4kRequest,
    method: HttpMethod,
    pathParameters: Map<String, String>,
    private val appContext: NetonContext?,
    override val traceId: String,
) : HttpContext {
    override val request: HttpRequest = HyperHttpRequest(request, method, pathParameters)
    internal val hyperResponse = HyperMemoryResponse()
    override val response: HttpResponse = hyperResponse
    override val session: HttpSession = MemoryHttpSession("hyper4k-$traceId")
    override val attributes: MutableMap<String, Any> = mutableMapOf()

    override fun getApplicationContext(): NetonContext? = appContext
}

private class HyperHttpRequest(
    private val source: Hyper4kRequest,
    override val method: HttpMethod,
    pathParameters: Map<String, String>,
) : HttpRequest {
    override val path: String = HyperHttpAdapter.normalizePath(source.path)
    override val url: String = if (source.query.isEmpty()) path else "$path?${source.query}"
    override val version: String = "HTTP/1.1"
    override val headers = MapHeaders(source.headers.mapValues { listOf(it.value) })
    override val queryParams: Parameters = HyperParameters(HyperHttpAdapter.parseParameters(source.query))
    override val pathParams: Parameters = HyperParameters(pathParameters.mapValues { listOf(it.value) })
    override val cookies: Map<String, Cookie> = parseCookies(source.header("Cookie"))
    override val remoteAddress: String =
        source.header("X-Forwarded-For")?.substringBefore(',')?.trim().orEmpty()
    override val isSecure: Boolean =
        source.header("X-Forwarded-Proto")?.equals("https", ignoreCase = true) == true

    override suspend fun body(): ByteArray = source.body.copyOf()
    override suspend fun text(): String = source.body.decodeToString()
    override suspend fun json(): Any = Json.parseToJsonElement(text())
    override suspend fun form(): Parameters =
        if (contentType?.substringBefore(';') == "application/x-www-form-urlencoded") {
            HyperParameters(HyperHttpAdapter.parseParameters(text()))
        } else {
            HyperParameters(emptyMap())
        }

    override suspend fun uploadFiles(): UploadFiles = UploadFiles(emptyList())

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

internal class HyperMemoryResponse : HttpResponse {
    override var status: HttpStatus = HttpStatus.OK
    override val headers: MutableHeaders = MapMutableHeaders()
    override val isCommitted: Boolean get() = body != null
    private var body: ByteArray? = null

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
        headers["Set-Cookie"] = value
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

    fun toHyper4kResponse(): Hyper4kResponse = Hyper4kResponse(
        status = status.code,
        headers = headers.toMap().mapValues { it.value.joinToString(", ") },
        body = body ?: ByteArray(0),
    )
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

private class HyperParameters(
    private val values: Map<String, List<String>>,
) : Parameters {
    override fun get(name: String): String? = values[name]?.firstOrNull()
    override fun getAll(name: String): List<String> = values[name].orEmpty()
    override fun contains(name: String): Boolean = name in values
    override fun names(): Set<String> = values.keys
    override fun toMap(): Map<String, List<String>> = values
}
