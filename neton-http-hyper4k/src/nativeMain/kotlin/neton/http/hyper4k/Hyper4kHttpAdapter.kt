package neton.http.hyper4k

import hyper4k.Hyper4kRequest
import hyper4k.Hyper4kResponse
import hyper4k.Hyper4kResponseChannel
import hyper4k.Hyper4kServer
import kotlinx.coroutines.delay
import neton.core.component.NetonContext
import neton.core.http.ParamConverterRegistry
import neton.core.http.adapter.HttpAdapter
import neton.core.http.adapter.HttpCapability
import neton.http.HttpServerConfig
import neton.http.adapter.BufferedHttpDispatcher
import neton.http.adapter.BufferedHttpRequest
import neton.http.adapter.BufferedHttpResponse
import neton.logging.LoggerFactory

/** Tokio + Hyper transport for Neton's standard buffered HTTP dispatcher. */
public class Hyper4kHttpAdapter(
    private val serverConfig: HttpServerConfig,
    @Suppress("UNUSED_PARAMETER") converterRegistry: ParamConverterRegistry,
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
            shutdownGraceMillis = minOf(serverConfig.timeout, 5_000L),
            failureResponse = { status, message ->
                dispatcher.transportFailureResponse(status, message).toHyper4k()
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
        dispatcher.dispatch(request.toBuffered()).toHyper4k()

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
        return if (live.isCommitted) Hyper4kResponse.streamed(live.status.code) else result.toHyper4k()
    }

    internal fun transportFailureResponse(status: Int, message: String): Hyper4kResponse =
        dispatcher.transportFailureResponse(status, message).toHyper4k()

    private fun logger() = appContext?.getOrNull(LoggerFactory::class)?.get("neton.http")
}

private fun Hyper4kRequest.toBuffered(): BufferedHttpRequest = BufferedHttpRequest(
    method = method,
    path = path,
    query = query,
    headers = headers,
    body = body,
)

private fun BufferedHttpResponse.toHyper4k(): Hyper4kResponse = Hyper4kResponse(
    status = status,
    headers = headers,
    body = body,
)
