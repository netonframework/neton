package neton.http.engine.default.client

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import neton.core.annotations.InternalNetonApi
import neton.core.http.HttpHeader
import neton.core.http.HttpHeaders
import neton.http.HttpError
import neton.http.client.ConnectError
import neton.http.client.HttpClient
import neton.http.client.HttpClientBody
import neton.http.client.HttpClientCapability
import neton.http.client.HttpClientConfig
import neton.http.client.HttpClientError
import neton.http.client.HttpClientException
import neton.http.client.HttpClientRequest
import neton.http.client.HttpClientResponse
import neton.http.client.HttpClientStreamChunk
import neton.http.client.HostResolver
import neton.http.client.OutboundAddressPolicy
import neton.http.client.TargetRejection
import neton.http.h1.Http1ClientConfig
import neton.http.uri.Uri
import neton.io.bytes.Bytes
import neton.io.core.IoException
import neton.io.net.ConnectException
import neton.openssl.TlsContext
import neton.tls.TlsHandshakeTimeoutException
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
// com.netonstream:http shares the package name `neton.http` with the framework; its types are
// imported under `Stream*` names, as in DefaultHttpAdapter.
import neton.http.Body as StreamBody
import neton.http.EmptyBody as StreamEmptyBody
import neton.http.Frame as StreamFrame
import neton.http.FullBody as StreamFullBody
import neton.http.Incoming as StreamIncoming
import neton.http.Method as StreamMethod
import neton.http.Request as StreamRequest
import neton.http.Response as StreamResponse
import neton.http.client.Client as StreamClient
import neton.http.header.HeaderValue as StreamHeaderValue

/**
 * Neton's built-in outbound [HttpClient] on the netonstream stack (spec zh-hans/spec/http-engine.md
 * §7): com.netonstream:http's pooling client (hyper-util `client::legacy::Client`), TLS with ALPN
 * `h2`/`http/1.1` from com.netonstream:tls over OpenSSL, on a neton-io reactor thread owned by this
 * client ([ClientReactor]).
 *
 * - Connections are pooled per `scheme://authority`; an HTTP/2 connection carries any number of
 *   requests at once. HTTP/2 is used when the server picks `h2` by ALPN.
 * - `Host` is the URL's authority, written for HTTP/1 only. A caller's own `Host` header is dropped:
 *   on HTTP/2 the authority travels as `:authority`, and a `host` field next to it is what strict
 *   servers (nginx) answer with 400 — the hyper4k client's bug this one must not repeat.
 * - Proxy (`proxyUrl`): `https` targets through a `CONNECT` tunnel (TLS end to end, pooled like a
 *   direct connection); `http` targets in absolute-form on a connection of their own per request. With an
 *   address policy both name the checked IP address, never the host name ([ClientConnector]).
 * - Timeouts (`toEffectiveTimeout()`, per-request overrides on top): `connectMillis` bounds TCP connect
 *   + proxy CONNECT + TLS handshake; `requestMillis` the whole exchange, body included (0 per request:
 *   none); `socketMillis` each wait for the next body frame (0 per request: none). A per-request
 *   `connectMillis` bounds the time to the response head, as the hyper4k client did.
 */
@OptIn(InternalNetonApi::class, ExperimentalAtomicApi::class)
internal class NetonStreamHttpClient(
    config: HttpClientConfig,
    /** Trust roots and ALPN for `https` targets; tests pass a context that trusts their own CA. */
    tls: TlsContext = defaultClientTls,
) : HttpClient {

    override val capabilities: Set<HttpClientCapability> = setOf(
        HttpClientCapability.HTTP_2,
        HttpClientCapability.STREAMING_BODY,
        HttpClientCapability.CANCELLATION,
        HttpClientCapability.PROXY,
        HttpClientCapability.ADDRESS_POLICY,
    )

    // toEffectiveTimeout() fills every field.
    private val defaults = config.toEffectiveTimeout()
    private val defaultConnectMillis: Long = defaults.connectMillis!!
    private val defaultRequestMillis: Long = defaults.requestMillis!!
    private val defaultSocketMillis: Long = defaults.socketMillis!!
    private val proxy: ProxyEndpoint? = config.proxyUrl?.let { url ->
        try {
            ProxyEndpoint.parse(url) { proxyClientTls }
        } catch (e: IllegalArgumentException) {
            throw HttpClientException(HttpClientError.Unknown("Invalid HTTP client config: ${e.message}", e))
        }
    }

    internal class State(val pool: StreamClient, val connector: ClientConnector, val root: CoroutineScope)

    private val connectorConfig = ConnectorConfig(
        defaultConnectMillis, proxy, tls, config.maxConnectionsPerHost, config.addressPolicy, config.hostResolver,
    )
    private val addressPolicy = config.addressPolicy

    private val reactor = ClientReactor(
        name = "neton-http-client",
        setUp = { root -> connectorConfig.build(root) },
        tearDown = { it.pool.close() },
    )

    private val closed = AtomicBoolean(false)

    override suspend fun request(request: HttpClientRequest): HttpClientResponse {
        ensureOpen()
        val limits = limitsOf(request)
        return reactor.run { state ->
            translated {
                timed(limits.total, "request") {
                    val exchange = timed(limits.head, "connect budget") { send(state, request) }
                    try {
                        val response = exchange.response
                        val body = ChunkBuffer()
                        while (true) {
                            val frame = timed(limits.idle, "read idle") { response.body.nextFrame() } ?: break
                            if (frame is StreamFrame.Data) body.append(frame.bytes)
                        }
                        HttpClientResponse(response.status.asU16(), response.neton(), body.join().decodeToString())
                    } finally {
                        exchange.close()
                    }
                }
            }
        }
    }

    override fun stream(request: HttpClientRequest): Flow<HttpClientStreamChunk> = flow {
        ensureOpen()
        val limits = limitsOf(request)
        val events = Channel<StreamEvent>(STREAM_QUEUE_CAPACITY)
        val pump: Job = reactor.launch { state ->
            try {
                translated {
                    timed(limits.total, "request") {
                        val exchange = timed(limits.head, "connect budget") { send(state, request) }
                        try {
                            val response = exchange.response
                            val status = response.status.asU16()
                            val headers = response.neton()
                            if (status !in 200..299) {
                                // An error JSON must not flow through an SSE parser as zero events; the
                                // body is diagnostics, capped.
                                val body = ChunkBuffer(limit = ERROR_BODY_LIMIT)
                                while (true) {
                                    val frame = timed(limits.idle, "read idle") { response.body.nextFrame() } ?: break
                                    if (frame is StreamFrame.Data) body.append(frame.bytes)
                                }
                                throw HttpClientException(HttpClientError.Http(status, "HTTP $status", body.join().decodeToString()))
                            }
                            events.send(StreamEvent.Head(status, headers))
                            while (true) {
                                val frame = timed(limits.idle, "read idle") { response.body.nextFrame() } ?: break
                                if (frame is StreamFrame.Data) events.send(StreamEvent.Chunk(frame.bytes.toByteArray()))
                            }
                            events.send(StreamEvent.Done(headers))
                        } finally {
                            // Not read to its end (the collector left, a deadline, an error): HTTP/1 closes
                            // the connection, HTTP/2 resets the stream. The origin stops sending.
                            exchange.close()
                        }
                    }
                }
                events.close()
            } catch (e: CancellationException) {
                // The collector cancelled us, or the client is closing: either way nobody waits for a
                // cause. A collector still reading sees the channel end without Done.
                events.close()
                throw e
            } catch (e: Throwable) {
                events.close(e)
            }
        }
        var finished = false
        try {
            for (event in events) {
                when (event) {
                    is StreamEvent.Head -> emit(HttpClientStreamChunk.Start(event.status, event.headers))
                    is StreamEvent.Chunk -> emit(HttpClientStreamChunk.Bytes(event.bytes))
                    is StreamEvent.Done -> {
                        finished = true
                        emit(HttpClientStreamChunk.End(event.headers))
                    }
                }
            }
            if (!finished) throw closedException()
        } finally {
            pump.cancel()
        }
    }

    override suspend fun close() {
        closed.store(true)
        reactor.stop()
    }

    private fun ensureOpen() {
        if (closed.load()) throw closedException()
    }

    // ---------------------------------------------------------------------------------------------
    // On the reactor
    // ---------------------------------------------------------------------------------------------

    /** A response head and how to let go of what carries it. */
    private class Exchange(val response: StreamResponse<StreamIncoming>, private val release: () -> Unit) {
        fun close() {
            response.body.close()
            release()
        }
    }

    private suspend fun send(state: State, request: HttpClientRequest): Exchange {
        val uri = try {
            Uri.parse(request.url)
        } catch (e: IllegalArgumentException) {
            throw HttpClientException(HttpClientError.Unknown("invalid request URL ${request.url}: ${e.message}", e))
        }
        val scheme = uri.schemeStr
        if (addressPolicy != null && scheme != "http" && scheme != "https") {
            throw HttpClientException(HttpClientError.TargetRejected(TargetRejection.SCHEME, "scheme '$scheme' is not http/https"))
        }
        if ((scheme != "http" && scheme != "https") || uri.authority == null) {
            throw HttpClientException(HttpClientError.Unknown("invalid request URL, an absolute http(s) URL is required: ${request.url}", null))
        }
        val streamRequest = request.toStream(uri)
        if (proxy != null && scheme == "http") return sendThroughProxy(state, uri, streamRequest)
        return Exchange(state.pool.request(streamRequest)) {}
    }

    /**
     * `http` through the proxy: absolute-form request line and `Host` from the URL (RFC 9112 §3.2.2),
     * on a connection to the proxy that this exchange owns. With an address policy the request line names
     * the checked IP address instead of the host name (the proxy must not resolve it). The pool rewrites every request into
     * origin-form, so this path does not go through it.
     */
    private suspend fun sendThroughProxy(state: State, uri: Uri, request: StreamRequest<StreamBody>): Exchange {
        val proxy = proxy!!
        val (connected, target) = state.connector.openForwardProxy(uri)
        val stream = connected.stream
        val (sender, connection) = Http1ClientConfig().handshake(stream)
        val run = state.root.launch { try { connection.run() } catch (e: Exception) { } }
        val authority = uri.authority.toString().substringAfterLast('@')
        // With an address policy the request target names the checked address; Host keeps the URL's authority.
        if (target !== uri) request.uri = target
        request.headers.insert("host", StreamHeaderValue.fromStr(authority))
        proxy.authorization?.let { request.headers.insert("proxy-authorization", StreamHeaderValue.fromStr(it)) }
        val response = try {
            sender.sendRequest(request)
        } catch (e: Throwable) {
            run.cancel()
            stream.close()
            throw e
        }
        return Exchange(response) {
            run.cancel()
            stream.close()
        }
    }

    private fun HttpClientRequest.toStream(uri: Uri): StreamRequest<StreamBody> {
        val builder = StreamRequest.builder().method(StreamMethod.fromStr(method.name.uppercase())).uri(uri)
        for (h in headers.asList()) {
            // Host is the URL's authority (class KDoc).
            if (h.name.equals("host", ignoreCase = true)) continue
            builder.header(h.name, h.value)
        }
        val bytes: ByteArray?
        val contentType: String?
        when (val b = body) {
            null -> { bytes = null; contentType = null }
            is HttpClientBody.Json -> { bytes = b.text.encodeToByteArray(); contentType = "application/json" }
            is HttpClientBody.Text -> { bytes = b.text.encodeToByteArray(); contentType = b.contentType }
            is HttpClientBody.Bytes -> { bytes = b.bytes; contentType = b.contentType }
        }
        if (contentType != null && !headers.contains("Content-Type")) builder.header("content-type", contentType)
        return builder.body(if (bytes == null) StreamEmptyBody else StreamFullBody(Bytes.wrap(bytes)))
    }

    private fun StreamResponse<StreamIncoming>.neton(): HttpHeaders {
        val lines = ArrayList<HttpHeader>()
        headers.forEach { name, value -> lines += HttpHeader(name.asStr(), value.tryToStr() ?: value.asBytes().decodeToString()) }
        return HttpHeaders.of(lines)
    }

    // ---------------------------------------------------------------------------------------------
    // Timeouts and errors
    // ---------------------------------------------------------------------------------------------

    private class Limits(val total: Long?, val head: Long?, val idle: Long?)

    private fun limitsOf(request: HttpClientRequest): Limits {
        val t = request.timeout
        val total = (t?.requestMillis ?: defaultRequestMillis).takeIf { it > 0 }
        val idle = (t?.socketMillis ?: defaultSocketMillis).takeIf { it > 0 }
        val head = t?.connectMillis?.takeIf { it > 0 && (total == null || it < total) }
        return Limits(total, head, idle)
    }

    /**
     * [block] within [millis], as a [HttpClientError.Timeout] naming [what]. An enclosing deadline that
     * fires while this one runs is not ours: our coroutine is then cancelled too, and the
     * cancellation passes through to the enclosing [timed].
     */
    private suspend fun <T> timed(millis: Long?, what: String, block: suspend () -> T): T {
        if (millis == null) return block()
        return try {
            withTimeout(millis) { block() }
        } catch (e: TimeoutCancellationException) {
            if (!currentCoroutineContext().isActive) throw e
            throw HttpClientException(HttpClientError.Timeout("$what exceeded ${millis}ms", e))
        }
    }

    private suspend fun <T> translated(block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        throw e.toHttpClientException()
    }

    private fun Throwable.toHttpClientException(): HttpClientException = when (this) {
        is HttpClientException -> this
        is ConnectTimeoutException, is TlsHandshakeTimeoutException, is neton.io.core.TimeoutException ->
            HttpClientException(HttpClientError.Timeout("connect: ${message ?: this}", this))
        is HttpError ->
            if (isTimeout()) HttpClientException(HttpClientError.Timeout("http: ${message ?: kind.description}", this))
            else HttpClientException(HttpClientError.Network("http: ${message ?: kind.description}", this))
        is ConnectError, is ConnectException ->
            HttpClientException(HttpClientError.Network("connect: ${message ?: this}", this))
        is IoException -> HttpClientException(HttpClientError.Network("${this::class.simpleName}: ${message ?: this}", this))
        is IllegalArgumentException -> HttpClientException(HttpClientError.Unknown("invalid request: ${message ?: this}", this))
        else -> HttpClientException(HttpClientError.Unknown("${this::class.simpleName}: ${message ?: this}", this))
    }

    private sealed class StreamEvent {
        class Head(val status: Int, val headers: HttpHeaders) : StreamEvent()
        class Chunk(val bytes: ByteArray) : StreamEvent()
        class Done(val headers: HttpHeaders) : StreamEvent()
    }

    private companion object {
        /** Body chunks the reactor reads ahead of a slow collector. */
        const val STREAM_QUEUE_CAPACITY = 8

        /** Error bodies on `stream()` are diagnostics; anything past this is dropped. */
        const val ERROR_BODY_LIMIT = 64 * 1024
    }
}

/** What [ClientConnector] and the pool need, captured on the caller's thread, built on the reactor. */
internal class ConnectorConfig(
    private val connectMillis: Long,
    private val proxy: ProxyEndpoint?,
    private val tls: TlsContext,
    private val maxConnectionsPerHost: Int?,
    private val addressPolicy: OutboundAddressPolicy?,
    private val hostResolver: HostResolver?,
) {
    fun build(root: CoroutineScope): NetonStreamHttpClient.State {
        val connector = ClientConnector(connectMillis, proxy, tls, maxConnectionsPerHost, addressPolicy, hostResolver)
        val pool = StreamClient.builder().build(root, connector)
        return NetonStreamHttpClient.State(pool, connector, root)
    }
}

/** Linear-time body accumulation: keep the chunks, copy once in [join]. */
private class ChunkBuffer(private val limit: Int = Int.MAX_VALUE) {
    private val chunks = ArrayList<ByteArray>()
    private var size = 0

    fun append(bytes: Bytes) {
        val room = limit - size
        if (room <= 0) return
        val all = bytes.toByteArray()
        val take = if (all.size <= room) all else all.copyOf(room)
        chunks += take
        size += take.size
    }

    fun join(): ByteArray {
        if (chunks.size == 1) return chunks[0]
        val out = ByteArray(size)
        var at = 0
        for (c in chunks) {
            c.copyInto(out, at)
            at += c.size
        }
        return out
    }
}

internal fun createNetonStreamHttpClient(config: HttpClientConfig): HttpClient = NetonStreamHttpClient(config)
