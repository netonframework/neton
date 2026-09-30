package neton.http.netonstream

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import neton.core.component.NetonContext
import neton.core.http.adapter.HttpAdapter
import neton.core.http.adapter.HttpCapability
import neton.core.http.adapter.HttpServerConfig
import neton.http.adapter.BufferedHttpDispatcher
import neton.http.adapter.BufferedHttpRequest
import neton.http.adapter.BufferedHttpResponse
import neton.io.core.IoStream
import neton.io.net.AcceptMode
import neton.io.net.GcTuning
import neton.io.net.TcpServerGroup
import neton.io.net.cpuCount
import neton.io.net.listenGroup
import neton.io.net.peerAddress
import neton.io.net.runReactor
import neton.logging.LoggerFactory
import neton.openssl.TlsContext
import neton.tls.tlsAccept
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.native.concurrent.ObsoleteWorkersApi
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker
// com.netonstream:http shares the package name `neton.http` with the framework (module README,
// "Package rule"). Its types are imported under `Stream*` names so the adapter never has two
// meanings for one simple name.
import neton.http.Body as StreamBody
import neton.http.Frame as StreamFrame
import neton.http.Request as StreamRequest
import neton.http.Response as StreamResponse
import neton.http.Version as StreamVersion
import neton.http.auto.AutoServerConfig
import neton.http.h1.Http1ServerConfig
import neton.http.h1.HttpService
import neton.http.h2.Http2ServerConfig
import neton.http.header.HeaderValue as StreamHeaderValue

/**
 * Engine options that [HttpServerConfig] does not carry. The defaults reproduce hyper4k's shape:
 * one reactor per online core (Tokio's worker count), 16 MiB request bodies.
 *
 * Select non-default options with a lambda instead of the constructor reference:
 * `http({ NetonStreamHttpAdapter(it, NetonStreamOptions(reactors = 2)) }) { }`.
 */
public class NetonStreamOptions(
    /** Listen address (hyper4k listens on 0.0.0.0). */
    public val host: String = "0.0.0.0",
    /** Reactor threads connections are spread over; one per online core by default. */
    public val reactors: Int = cpuCount(),
    /** How accepted connections reach the reactors (neton-io; ReusePort is Linux/Android only). */
    public val acceptMode: AcceptMode = AcceptMode.Handoff,
    /** Request body limit on HTTP/1 and HTTP/2; beyond it the request is answered with 413. */
    public val maxRequestBodyBytes: Long = DEFAULT_MAX_REQUEST_BODY_BYTES,
    /** Chunks a streaming handler may queue ahead of the connection before `writeChunk` waits. */
    public val streamQueueCapacity: Int = DEFAULT_STREAM_QUEUE_CAPACITY,
    /** Time a client gets to complete the TLS handshake. */
    public val tlsHandshakeTimeoutMillis: Long = DEFAULT_TLS_HANDSHAKE_TIMEOUT_MILLIS,
    public val maxOpenConnections: Int = 16_384,
    public val maxBufferedRequestBytes: Long = 128L * 1024 * 1024,
    public val requestBodyTimeoutMillis: Long = 30_000,
) {
    init {
        require(reactors >= 1) { "reactors must be >= 1" }
        require(maxRequestBodyBytes in 1..(Int.MAX_VALUE / 2).toLong()) { "invalid request body limit" }
        require(maxOpenConnections > 0) { "maxOpenConnections must be positive" }
        require(maxBufferedRequestBytes >= 2 * maxRequestBodyBytes) {
            "maxBufferedRequestBytes must be at least twice maxRequestBodyBytes (one body plus its resize copy)"
        }
        require(requestBodyTimeoutMillis > 0) { "requestBodyTimeoutMillis must be positive" }
        require(streamQueueCapacity >= 1) { "streamQueueCapacity must be >= 1" }
        require(tlsHandshakeTimeoutMillis > 0) { "tlsHandshakeTimeoutMillis must be positive" }
    }

    public companion object {
        /** hyper4k's `MAX_REQUEST_BODY_BYTES`. */
        public const val DEFAULT_MAX_REQUEST_BODY_BYTES: Long = 16L * 1024 * 1024
        public const val DEFAULT_STREAM_QUEUE_CAPACITY: Int = 8
        public const val DEFAULT_TLS_HANDSHAKE_TIMEOUT_MILLIS: Long = 10_000
    }
}

/**
 * Neton's HTTP engine on the netonstream Kotlin/Native stack: neton-io reactors (com.netonstream:io),
 * HTTP/1.1 and HTTP/2 on one port (com.netonstream:http, hyper-util `auto`), TLS with ALPN
 * (com.netonstream:tls over OpenSSL). Requests go through the framework's shared
 * [BufferedHttpDispatcher], like every other engine.
 *
 * Externally it behaves as the hyper4k adapter does (module README has the table): request timeout
 * → 504, over [HttpServerConfig.maxConnections] concurrent requests → 503, bodies over 16 MiB → 413,
 * gzip by the same policy, graceful stop within min(timeout, 5 s).
 *
 * Handlers start inline on the connection's reactor thread and continue on [Dispatchers.Default]
 * once they suspend (hyper4k starts them inline on the Tokio worker and continues on the same
 * dispatcher). Blocking/non-suspending work still occupies the reactor and must be moved off it.
 */
@OptIn(ExperimentalAtomicApi::class, ObsoleteWorkersApi::class)
public class NetonStreamHttpAdapter(
    private val serverConfig: HttpServerConfig,
    private val options: NetonStreamOptions = NetonStreamOptions(),
) : HttpAdapter {
    init {
        require(serverConfig.maxConnections > 0) { "maxConnections must be positive" }
        require(serverConfig.timeout >= 0) { "timeout must not be negative" }
    }

    private val dispatcher = BufferedHttpDispatcher(serverConfig)
    private var appContext: NetonContext? = null
    private var running: ServerRun? = null
    private val lifecycle = Mutex()
    private val bufferedBytes = AtomicLong(0)
    internal var afterWorkerStarted: (suspend () -> Unit)? = null

    /** Concurrent-request admission, hyper4k's semantics: a full house or a stopping server answers 503. */
    private val accepting = AtomicBoolean(true)
    private val activeRequests = AtomicInt(0)
    private val handlerJob = SupervisorJob()
    private val handlerScope = CoroutineScope(handlerJob + Dispatchers.Default)

    /** hyper4k: `timeout = 0` turns the per-request timeout off but keeps a 5 s drain on stop. */
    private val shutdownGraceMillis: Long =
        if (serverConfig.timeout > 0) minOf(serverConfig.timeout, 5_000L) else 5_000L

    private val autoConfig = AutoServerConfig(
        // HTTP/1 request bodies are timed by the connection itself (armed only once it has to wait for body bytes);
        // HTTP/2 streams are timed around the read in handle().
        Http1ServerConfig(maxRequestBodySize = options.maxRequestBodyBytes, bodyReadTimeoutMillis = options.requestBodyTimeoutMillis),
        Http2ServerConfig(),
    )


    override val capabilities: Set<HttpCapability> = setOf(
        HttpCapability.ASYNC_HANDOFF,
        // Earned by tests, not asserted: the conformance suite's streaming checks and the
        // adapter's h2c / TLS-ALPN tests fail the build if either stops holding.
        HttpCapability.STREAMING_RESPONSE,
        HttpCapability.HTTP_2,
    )

    override fun port(): Int = serverConfig.port

    override fun adapterName(): String = "NetonStream"

    override suspend fun start(ctx: NetonContext, onStarted: (suspend (Long) -> Unit)?) {
        val run = lifecycle.withLock {
            check(running == null) { "netonstream server already started" }
            check(!handlerJob.isCancelled) { "a stopped NetonStreamHttpAdapter cannot be started again" }
            bindContext(ctx)
            GcTuning.fromEnvironment()
            val tlsContext = serverConfig.tls?.let { tls ->
                val certificate = runCatching { readFileBytes(tls.certificatePath) }
                val key = runCatching { readFileBytes(tls.privateKeyPath) }
                if (certificate.isFailure || key.isFailure) {
                    error(
                        "netonstream server failed to start on ${options.host}:${serverConfig.port} " +
                            "(certificate/key unreadable at ${tls.certificatePath} / ${tls.privateKeyPath})",
                    )
                }
                TlsContext(
                    server = true,
                    certificateChainPem = certificate.getOrThrow(),
                    privateKeyPem = key.getOrThrow(),
                    alpnProtocols = tls.alpnProtocols,
                )
            }
            try {
                ServerRun(this, tlsContext).also { it.start(); running = it }
            } catch (e: Throwable) {
                tlsContext?.close()
                appContext = null
                if (e is CancellationException) throw e
                throw IllegalStateException(
                    "netonstream server failed to start on ${options.host}:${serverConfig.port} (${e.message ?: e})",
                    e,
                )
            }
        }
        try {
            val coldStart = kotlin.time.Clock.System.now().toEpochMilliseconds() - run.startedAt
            logger()?.info(
                "neton.http.netonstream.started",
                buildMap {
                    put("port", serverConfig.port)
                    put("reactors", options.reactors)
                    serverConfig.tls?.let { put("alpn", it.alpnProtocols.joinToString(",").ifEmpty { "none" }) }
                },
            )
            onStarted?.invoke(coldStart)
            run.stopped.await()
        } finally {
            // Leaving start() for any reason (stop, or the caller's cancellation) must not leave the server behind.
            stop()
        }
    }

    /**
     * Stops admitting requests (503 from now on), stops accepting connections and asks each one to
     * finish gracefully (HTTP/1: no more keep-alive; HTTP/2: GOAWAY), drains in-flight requests for
     * up to min(timeout, 5 s), then closes what is left and cancels the handlers still running.
     */
    override suspend fun stop() {
        withContext(NonCancellable) {
            lifecycle.withLock {
                val run = running ?: return@withLock
                accepting.store(false)
                run.requestStop()
                run.stopped.await()
                handlerJob.cancel()
                withTimeoutOrNull(shutdownGraceMillis) { handlerJob.join() }
                run.tls?.close()
                appContext = null
                running = null
            }
        }
    }

    internal fun bindContext(ctx: NetonContext) {
        appContext = ctx
        dispatcher.bind(ctx)
    }

    // ---------------------------------------------------------------------------------------------
    // Connections
    // ---------------------------------------------------------------------------------------------

    /** One accepted connection, on its reactor: TLS if configured, then HTTP/1 or HTTP/2 until it ends. */
    private suspend fun serveConnection(stream: IoStream, tls: TlsContext?, shutdown: CompletableDeferred<Unit>) {
        // The socket's peer, taken before TLS wraps it; an IPv4 client of a dual-stack listener as IPv4.
        val peer = stream.peerAddress?.toCanonical()?.ipString() ?: ""
        val io: IoStream
        val alpn: String?
        if (tls != null) {
            val secured = try {
                withTimeout(options.tlsHandshakeTimeoutMillis) { tlsAccept(stream, tls) }
            } catch (e: TimeoutCancellationException) {
                stream.close()
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A failed handshake is the peer's business, not an error here (hyper4k: the same).
                return
            }
            io = secured
            alpn = secured.alpn
        } else {
            io = stream
            alpn = null
        }
        // Handlers of this connection are children of its own job, attached to the adapter's once per connection. A
        // handler per request attached to one job shared by every reactor made all cores contend on that job's child
        // list (measured: 180 ns per request on one thread, 3.7 us on each of eight).
        val connectionHandlers = SupervisorJob(handlerJob)
        val handlerScope = CoroutineScope(connectionHandlers + Dispatchers.Default)
        val connection = autoConfig.serveConnection(io, alpn, HttpService { request -> handle(request, peer, handlerScope) })
        try {
            serve(connection, shutdown)
        } finally {
            // The connection is over: whatever its handlers still do has no one to answer (as a client that goes away).
            connectionHandlers.cancel()
        }
    }

    private suspend fun serve(connection: neton.http.auto.AutoConnection, shutdown: CompletableDeferred<Unit>) {
        coroutineScope {
            // Resumes on this connection's reactor, where gracefulShutdown must be called.
            val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
                shutdown.await()
                connection.gracefulShutdown()
            }
            try {
                connection.serve()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A protocol error, a reset, a timeout or a client that went away ends this
                // connection only; the library already sent what the protocol calls for.
            } finally {
                watcher.cancel()
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Requests
    // ---------------------------------------------------------------------------------------------

    /**
     * One request: admit it (503 when stopping or full), read the body (413 over the limit), run
     * the handler, answer with a complete or a streamed body.
     *
     * Takes any body so the conformance suite can call it in-process with the library's
     * `FullBody`; the connection hands it its own `Incoming`.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    internal suspend fun handle(
        request: StreamRequest<out StreamBody>,
        peer: String = "",
        handlerScope: CoroutineScope = this.handlerScope,
    ): StreamResponse<out StreamBody> {
        val connectionJob = currentCoroutineContext()[Job]
        if (!accepting.load() || !admit()) {
            return rejection(503, "Service Unavailable", !request.body.isEndStream).toStreamResponse(connectionJob)
        }
        var handedOff = false
        var reservation = 0L
        try {
            if (!accepting.load()) return rejection(503, "Service Unavailable", !request.body.isEndStream).toStreamResponse(connectionJob)
            if (!request.body.isEndStream) {
                // Reserve a small initial buffer, then grow admission before growing the array.
                val required = 2 * minOf(options.maxRequestBodyBytes, 1024)
                if (!reserveBody(required)) return rejection(503, "Service Unavailable").toStreamResponse(connectionJob)
                reservation = required
            }
            val body = try {
                if (request.body.isEndStream) EMPTY
                else {
                    val grow: (Int) -> Unit = { capacity ->
                        val required = 2L * capacity
                        val extra = required - reservation
                        if (extra > 0) {
                            if (!reserveBody(extra)) throw BodyBudgetExceeded()
                            reservation = required
                        }
                    }
                    // HTTP/1: the connection enforces requestBodyTimeoutMillis (Http1ServerConfig.bodyReadTimeoutMillis) and
                    // a buffered body never arms it; a kotlinx withTimeout here cost about 6,000 instructions per POST.
                    if (request.version === StreamVersion.HTTP_2) {
                        withTimeout(options.requestBodyTimeoutMillis) { readBody(request.body, options.maxRequestBodyBytes, grow) }
                    } else {
                        readBody(request.body, options.maxRequestBodyBytes, grow)
                    }
                }
            } catch (_: TimeoutCancellationException) {
                return bodyTimeout().toStreamResponse(connectionJob)
            } catch (e: neton.http.HttpError) {
                if (!e.isTimeout()) throw e
                return bodyTimeout().toStreamResponse(connectionJob)
            } catch (_: BodyBudgetExceeded) {
                return rejection(503, "Service Unavailable").toStreamResponse(connectionJob)
            } ?: return bodyTooLarge().toStreamResponse(connectionJob)
            val acceptEncoding = headerString(request, "accept-encoding")
            val buffered = request.toBuffered(body, peer)
            val ready = CompletableDeferred<EngineResponse>()
            val live = NetonStreamLiveResponse(
                corsHeaders = dispatcher.corsHeaders(buffered),
                channelCapacity = options.streamQueueCapacity,
                onStreamStart = { ready.complete(it) },
            )
            // UNDISPATCHED: a handler that does not suspend completes right here, on the reactor, and
            // `ready` is already complete below; one that suspends continues on Dispatchers.Default.
            val reserved = reservation
            val handler = handlerScope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    currentCoroutineContext().ensureActive()
                    val response = if (serverConfig.timeout > 0) {
                        lazyTimeout(serverConfig.timeout) { respond(buffered, live, acceptEncoding) }
                    } else {
                        respond(buffered, live, acceptEncoding)
                    }
                    if (response != null) ready.complete(response)
                } catch (_: TimeoutCancellationException) {
                    // Once streaming, the head is out: nothing can be sent but the end of the body.
                    if (!live.isStreaming) ready.complete(failure(504, "Gateway Timeout"))
                } catch (_: CancellationException) {
                    if (!live.isStreaming) ready.complete(failure(503, "Service Unavailable"))
                } catch (_: Throwable) {
                    if (!live.isStreaming) ready.complete(failure(500, "Internal Server Error"))
                } finally {
                    release(reserved)
                }
            }
            handedOff = true
            if (!handler.isCompleted) {
                val watch = connectionJob?.invokeOnCompletion { handler.cancel() }
                handler.invokeOnCompletion { watch?.dispose() }
            }
            val response = try {
                if (ready.isCompleted) ready.getCompleted() else ready.await()
            } catch (e: CancellationException) {
                handler.cancel()
                throw e
            }
            response.stream?.bindProducer(handler)
            if (response.stream != null &&
                (request.method.asStr() == "HEAD" || response.status in 100..199 || response.status == 204 || response.status == 304)) {
                response.stream.markGone()
                return EngineResponse(response.status, response.headers, EMPTY).toStreamResponse(connectionJob)
            }
            return response.toStreamResponse(connectionJob)
        } finally {
            if (!handedOff) release(reservation)
        }
    }

    /** The handler's answer; null when it streamed (its head already went through `onStreamStart`). */
    private suspend fun respond(
        buffered: BufferedHttpRequest,
        live: NetonStreamLiveResponse,
        acceptEncoding: String?,
    ): EngineResponse? {
        val result = dispatcher.dispatch(buffered, live)
        return when {
            live.isStreaming -> null
            live.isCommitted -> maybeCompress(serverConfig.enableCompression, acceptEncoding, live.completeResponse())
            else -> maybeCompress(serverConfig.enableCompression, acceptEncoding, result.toEngine())
        }
    }

    private fun reserveBody(bytes: Long): Boolean {
        while (true) {
            val used = bufferedBytes.load()
            if (bytes > options.maxBufferedRequestBytes - used) return false
            if (bufferedBytes.compareAndSet(used, used + bytes)) return true
        }
    }

    /** One more request in flight, unless [HttpServerConfig.maxConnections] are (hyper4k: 503 when full). */
    private fun admit(): Boolean {
        val max = serverConfig.maxConnections
        while (true) {
            val n = activeRequests.load()
            if (n >= max) return false
            if (activeRequests.compareAndSet(n, n + 1)) return true
        }
    }

    private fun release(reservation: Long = 0) {
        if (reservation != 0L) bufferedBytes.addAndFetch(-reservation)
        activeRequests.addAndFetch(-1)
    }

    /** Requests admitted and not yet finished (streams included). */
    internal val inFlightRequests: Int get() = activeRequests.load()

    /** Tests: per-connection handler jobs still attached (each one leaves when its connection ends). */
    internal val connectionHandlerJobs: Int get() = handlerJob.children.count()
    internal val reservedRequestBytes: Long get() = bufferedBytes.load()

    private fun rejection(status: Int, message: String, close: Boolean = true): EngineResponse {
        val response = failure(status, message)
        return if (close) EngineResponse(response.status, response.headers + ("Connection" to listOf("close")), response.body) else response
    }

    private fun failure(status: Int, message: String): EngineResponse =
        dispatcher.transportFailureResponse(status, message).toEngine()

    private fun logger() = appContext?.getOrNull(LoggerFactory::class)?.get("neton.http")

    /**
     * Runs the reactors on a thread of their own: [HttpAdapter.start] is called inside the
     * application's `runBlocking`, and neton-io's reactor 0 blocks the thread it runs on.
     */
    private class ServerRun(val adapter: NetonStreamHttpAdapter, val tls: TlsContext?) {
        val startedAt = kotlin.time.Clock.System.now().toEpochMilliseconds()
        private val bound = CompletableDeferred<Unit>()
        private val stopRequested = CompletableDeferred<Unit>()
        private val shutdownSignal = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        private val worker = Worker.start(name = "neton-stream-reactor-0")

        suspend fun start() {
            worker.execute(TransferMode.SAFE, { this }) { it.serveOnThisThread() }
            try {
                adapter.afterWorkerStarted?.invoke()
                bound.await()
            } catch (e: Throwable) {
                withContext(NonCancellable) {
                    requestStop()
                    stopped.await()
                }
                throw e
            }
        }

        fun requestStop() {
            stopRequested.complete(Unit)
        }

        private fun serveOnThisThread() {
            try {
                runReactor {
                    val options = adapter.options
                    val group: TcpServerGroup = try {
                        listenGroup(
                            host = options.host,
                            port = adapter.serverConfig.port,
                            reactors = options.reactors,
                            acceptMode = options.acceptMode,
                            maxConnections = options.maxOpenConnections,
                        )
                    } catch (e: Throwable) {
                        bound.completeExceptionally(e)
                        return@runReactor
                    }
                    bound.complete(Unit)
                    val serving = launch {
                        group.serve { stream -> adapter.serveConnection(stream, tls, shutdownSignal) }
                    }
                    stopRequested.await()
                    // Stop accepting, then let every connection wind down on its own reactor.
                    group.close()
                    shutdownSignal.complete(Unit)
                    withTimeoutOrNull(adapter.shutdownGraceMillis) {
                        while (adapter.activeRequests.load() > 0 || group.activeConnections > 0) delay(DRAIN_POLL_MILLIS)
                    }
                    // hyper4k aborts the connections that are left once the grace period is over.
                    group.shutdown(0)
                    serving.join()
                    group.awaitWorkers()
                }
            } catch (e: Throwable) {
                bound.completeExceptionally(e)
            } finally {
                stopped.complete(Unit)
                worker.requestTermination(processScheduledJobs = false)
            }
        }
    }

    private companion object {
        const val DRAIN_POLL_MILLIS = 5L
    }
}

private class BodyBudgetExceeded : Exception()

/** Reads the whole request body; null when it is over [limit] (HTTP/2, or a body without the h1 limit). */
private suspend fun readBody(body: StreamBody, limit: Long, reserveCapacity: (Int) -> Unit): ByteArray? {
    if (body.isEndStream) return EMPTY
    val declared = body.exactLength
    if (declared > limit) return null
    val initialCapacity = if (declared in 1..limit) declared.toInt() else 0
    reserveCapacity(initialCapacity)
    var out = ByteArray(initialCapacity)
    var size = 0
    while (true) {
        // An HTTP/1 body over Http1ServerConfig.maxRequestBodySize throws the library's
        // UserBodyTooLarge here; the connection answers that with 413 and closes, so it propagates.
        val frame = body.nextFrame() ?: break
        if (frame !is StreamFrame.Data) continue
        val bytes = frame.bytes
        if (size.toLong() + bytes.size > limit) return null
        if (size + bytes.size > out.size) {
            val capacity = minOf(limit.toInt(), maxOf(out.size * 2, size + bytes.size, 1024))
            reserveCapacity(capacity)
            out = out.copyOf(capacity)
        }
        bytes.copyInto(out, size)
        size += bytes.size
    }
    return if (size == out.size) out else out.copyOf(size)
}

private val EMPTY = ByteArray(0)

/**
 * A client that does not finish sending its body within `requestBodyTimeoutMillis`: 408 (RFC 9110
 * §15.5.9, the client was too slow), not 504, which names a timeout of the server's own upstream.
 * The body is left unread, so the connection closes.
 */
private fun bodyTimeout() = EngineResponse(
    status = 408,
    headers = mapOf("Content-Type" to listOf("text/plain; charset=utf-8"), "Connection" to listOf("close")),
    body = "request body timeout".encodeToByteArray(),
)

/** hyper4k answers an oversized body with 413 and this text, before the handler is involved. */
private fun bodyTooLarge() = EngineResponse(
    status = 413,
    headers = mapOf("Content-Type" to listOf("text/plain; charset=utf-8")),
    body = "request body too large".encodeToByteArray(),
)

/**
 * The library request as the framework's [BufferedHttpRequest]. Header lookups go straight to the
 * library's [neton.http.header.HeaderMap] (case-insensitive); the full map is only built when
 * something asks for all headers, as the hyper4k adapter does.
 *
 * [peer] is the socket's peer IP (empty in-process); the framework prefers X-Forwarded-For for
 * `remoteAddress` and keeps the socket peer as `peerAddress`.
 */
private fun StreamRequest<*>.toBuffered(body: ByteArray, peer: String): BufferedHttpRequest {
    val headers = this.headers
    val uri = this.uri
    return BufferedHttpRequest(
        method = method.asStr(),
        path = uri.path.ifEmpty { "/" },
        query = uri.query ?: "",
        body = body,
        remoteAddress = peer,
        singleHeader = { name -> headers[name]?.let(::headerText) },
        headersProvider = {
            val map = LinkedHashMap<String, MutableList<String>>()
            headers.forEach { name, value -> map.getOrPut(name.asStr()) { ArrayList(1) }.add(headerText(value)) }
            map
        },
    )
}

private fun headerString(request: StreamRequest<*>, name: String): String? = request.headers[name]?.let(::headerText)

/** Header bytes as text; values outside visible ASCII are decoded as UTF-8 rather than dropped. */
private fun headerText(value: StreamHeaderValue): String = value.tryToStr() ?: value.asBytes().decodeToString()

private fun BufferedHttpResponse.toEngine(): EngineResponse = EngineResponse(status, headers, body)

/**
 * Runs [block] without arming a timer unless it actually suspends (hyper4k's `lazyTimeout`):
 * a handler that never suspends cannot be interrupted by a timeout anyway, and arming one for
 * every request costs two coroutine objects per request.
 */
private suspend fun <R> lazyTimeout(timeoutMillis: Long, block: suspend () -> R): R = coroutineScope {
    var completedInline = false
    val worker = async(start = CoroutineStart.UNDISPATCHED) {
        val result = block()
        completedInline = true
        result
    }
    if (completedInline) worker.await() else withTimeout(timeoutMillis) { worker.await() }
}
