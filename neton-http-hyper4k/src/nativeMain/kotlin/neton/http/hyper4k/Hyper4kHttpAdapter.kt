package neton.http.hyper4k

import neton.core.http.adapter.HttpServerConfig
import hyper4k.Hyper4kTls

import hyper4k.Hyper4kRequest
import hyper4k.Hyper4kResponse
import hyper4k.Hyper4kResponseChannel
import hyper4k.Hyper4kServer
import kotlinx.coroutines.delay
import neton.core.component.NetonContext
import neton.core.http.adapter.HttpAdapter
import neton.core.http.adapter.HttpCapability
import neton.http.adapter.BufferedHttpDispatcher
import neton.http.adapter.BufferedHttpRequest
import neton.http.adapter.BufferedHttpResponse
import neton.logging.LoggerFactory

/** Tokio + Hyper transport for Neton's standard buffered HTTP dispatcher. */
public class Hyper4kHttpAdapter(
    private val serverConfig: HttpServerConfig,
) : HttpAdapter {
    private val dispatcher = BufferedHttpDispatcher(serverConfig)
    private var server: Hyper4kServer? = null
    private var appContext: NetonContext? = null

    override val capabilities: Set<HttpCapability> = setOf(
        HttpCapability.ASYNC_HANDOFF,
        // Both are earned, not asserted: the conformance suite's streaming checks and
        // hyper4k's h2c test fail the build if either of these stops holding.
        HttpCapability.STREAMING_RESPONSE,
        HttpCapability.HTTP_2,
    )

    override fun port(): Int = serverConfig.port

    override fun adapterName(): String = "Hyper4k"

    override suspend fun start(ctx: NetonContext, onStarted: (suspend (Long) -> Unit)?) {
        check(server == null) { "hyper4k server already started" }
        bindContext(ctx)
        val startedAt = kotlin.time.Clock.System.now().toEpochMilliseconds()
        val running = Hyper4kServer(
            host = "0.0.0.0",
            port = serverConfig.port,
            maxConcurrentRequests = serverConfig.maxConnections,
            requestTimeoutMillis = serverConfig.timeout,
            // timeout = 0 表示关掉每请求超时，但停机仍要留出排空时间——直接取 min
            // 会把优雅停机也一起归零。
            shutdownGraceMillis = if (serverConfig.timeout > 0) minOf(serverConfig.timeout, 5_000L) else 5_000L,
            failureResponse = { status, message ->
                dispatcher.transportFailureResponse(status, message).toHyper4k()
            },
            tls = serverConfig.tls?.let {
                Hyper4kTls(it.certificatePath, it.privateKeyPath, it.alpnProtocols)
            },
        )
        running.start { request, channel -> dispatch(request, channel) }
        server = running
        val coldStart = kotlin.time.Clock.System.now().toEpochMilliseconds() - startedAt
        logger()?.info("neton.http.hyper4k.started", mapOf("port" to serverConfig.port))
        onStarted?.invoke(coldStart)
        while (server != null) delay(250)
    }

    override suspend fun stop() {
        val running = server ?: return
        server = null
        running.stop()
        appContext = null
    }

    internal fun bindContext(ctx: NetonContext) {
        appContext = ctx
        dispatcher.bind(ctx)
    }

    internal suspend fun dispatch(request: Hyper4kRequest): Hyper4kResponse =
        maybeCompress(request, dispatcher.dispatch(request.toBuffered()).toHyper4k())

    /**
     * gzip the response body when the client asked for it and the payload is
     * worth it: Accept-Encoding offers gzip, the body is a compressible type and
     * over [MIN_COMPRESS_BYTES], it is not already encoded, and it is a buffered
     * (non-streamed) response. Otherwise the response is returned untouched, so a
     * request without Accept-Encoding never gets a Content-Encoding header.
     */
    private fun maybeCompress(request: Hyper4kRequest, resp: Hyper4kResponse): Hyper4kResponse {
        if (!serverConfig.enableCompression) return resp
        if (resp.streamed || resp.body.size < MIN_COMPRESS_BYTES) return resp
        // Never re-encode, never touch a partial/range representation, and honour
        // Cache-Control: no-transform (RFC 9110 §7.7 forbids transforming it).
        if (resp.status == 206) return resp
        val h = resp.headers
        if (h.keys.any { it.equals("Content-Encoding", ignoreCase = true) }) return resp
        if (headerValues(h, "Cache-Control").any { it.contains("no-transform", ignoreCase = true) }) return resp
        // Client must actually accept gzip with a non-zero q-value ("gzip;q=0" is a
        // refusal), so a bare contains() is wrong.
        if (!acceptsGzip(request.header("accept-encoding"))) return resp
        val contentType = headerValues(h, "Content-Type").firstOrNull()
        if (contentType == null || !isCompressibleType(contentType)) return resp
        val gz = hyper4k.gzip(resp.body) ?: return resp
        if (gz.size >= resp.body.size) return resp
        val headers = LinkedHashMap<String, List<String>>(resp.headers.size + 2)
        for ((k, v) in resp.headers) {
            // Drop any Content-Length: the engine writes the compressed length, and
            // a stale header would misframe the response.
            if (k.equals("Content-Length", ignoreCase = true)) continue
            if (k.equals("Vary", ignoreCase = true)) continue
            headers[k] = v
        }
        headers["Content-Encoding"] = listOf("gzip")
        // Merge into an existing Vary (e.g. Origin) rather than clobbering it.
        val priorVary = headerValues(resp.headers, "Vary")
        headers["Vary"] = if (priorVary.any { it.contains("Accept-Encoding", ignoreCase = true) }) {
            priorVary
        } else {
            priorVary + "Accept-Encoding"
        }
        return Hyper4kResponse(resp.status, headers, gz, resp.streamed)
    }

    private fun headerValues(h: Map<String, List<String>>, name: String): List<String> =
        h.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value ?: emptyList()

    /** True only if Accept-Encoding offers gzip with a q-value other than 0. */
    private fun acceptsGzip(acceptEncoding: String?): Boolean {
        if (acceptEncoding == null) return false
        for (part in acceptEncoding.split(',')) {
            val token = part.trim()
            val coding = token.substringBefore(';').trim()
            if (!coding.equals("gzip", ignoreCase = true) && coding != "*") continue
            val q = token.substringAfter(";", "").split(';')
                .firstOrNull { it.trim().startsWith("q=", ignoreCase = true) }
                ?.substringAfter("=")?.trim()?.toDoubleOrNull()
            if (q == null || q > 0.0) return true
        }
        return false
    }

    private fun isCompressibleType(contentType: String): Boolean {
        val ct = contentType.substringBefore(';').trim().lowercase()
        return ct.startsWith("application/json") ||
            ct.startsWith("text/") ||
            ct == "application/javascript" ||
            ct == "application/xml" ||
            ct.endsWith("+json") ||
            ct.endsWith("+xml")
    }

    /**
     * Dispatch with a live streaming channel.
     *
     * A handler that committed itself (write / stream / redirect) is not written
     * out a second time: its headers are already on the wire. Same shape as the
     * Ktor adapter.
     */
    internal suspend fun dispatch(
        request: Hyper4kRequest,
        channel: Hyper4kResponseChannel,
    ): Hyper4kResponse {
        val buffered = request.toBuffered()
        // CORS headers must reach the live response before it commits: once a stream
        // starts writing, no header can be added.
        val live = Hyper4kLiveResponse(channel, dispatcher.corsHeaders(buffered))
        val result = dispatcher.dispatch(buffered, live)
        return when {
            // Headers are already on the wire; the engine must only close the stream.
            live.isStreaming -> Hyper4kResponse.streamed(live.status.code)
            // A complete body: hand it back so the engine writes it inline instead
            // of pushing it through the blocking write pool. Compress it if the
            // client asked and it is worth it (json-comp).
            live.isCommitted -> maybeCompress(request, live.completeResponse())
            else -> maybeCompress(request, result.toHyper4k())
        }
    }

    internal fun transportFailureResponse(status: Int, message: String): Hyper4kResponse =
        dispatcher.transportFailureResponse(status, message).toHyper4k()

    private fun logger() = appContext?.getOrNull(LoggerFactory::class)?.get("neton.http")
}

/**
 * Hand the dispatcher the header block unparsed.
 *
 * Passing `headers` here read the engine's lazily-parsed map, so every request
 * built it — a map plus two strings and a list per header — even though dispatch
 * only reads `X-Request-Id` on the way in and nothing else usually looks. The
 * scan and the map are cross-checked in hyper4k's own tests.
 */
/** Bodies below this are not worth gzip's CPU + header overhead. */
private const val MIN_COMPRESS_BYTES = 256

private fun Hyper4kRequest.toBuffered(): BufferedHttpRequest = BufferedHttpRequest(
    method = method,
    path = path,
    query = query,
    body = body,
    remoteAddress = "",
    singleHeader = ::header,
    headersProvider = ::headers,
)

private fun BufferedHttpResponse.toHyper4k(): Hyper4kResponse = Hyper4kResponse(
    status = status,
    headers = headers,
    body = body,
)
