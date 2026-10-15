package neton.http.engine.default.client

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import neton.http.engine.default.TestIdentity
import neton.http.engine.default.ignoreSigpipe
import neton.io.bytes.Buffer
import neton.io.bytes.Bytes
import neton.io.core.IoStream
import neton.io.net.TcpListener
import neton.io.net.connect
import neton.io.net.listen
import neton.io.net.runReactor
import neton.openssl.TlsContext
import neton.tls.tlsAccept
import kotlin.native.concurrent.ObsoleteWorkersApi
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker
import neton.http.Frame as StreamFrame
import neton.http.FullBody as StreamFullBody
import neton.http.Response as StreamResponse
import neton.http.auto.AutoServerConfig
import neton.http.h1.HttpService

/** What an origin saw of one request: protocol version, request target authority, header fields. */
internal class SeenRequest(
    val version: String,
    val authority: String?,
    val path: String,
    val headers: List<Pair<String, String>>,
    val body: ByteArray,
) {
    fun all(name: String): List<String> = headers.filter { it.first.equals(name, ignoreCase = true) }.map { it.second }
}

/**
 * A loopback origin on a reactor thread of its own, serving HTTP/1.1 and HTTP/2 with com.netonstream:http's
 * server: over TLS with ALPN when [tls] is given (`h2` when the client offers it), cleartext HTTP/1.1
 * otherwise. Every request is recorded in [seen] and answered by [respond] (which may suspend).
 */
@OptIn(ObsoleteWorkersApi::class)
internal class TestOrigin private constructor(
    private val tls: TlsContext?,
    private val respond: suspend (SeenRequest) -> Pair<Int, ByteArray>,
) {
    val seen = Channel<SeenRequest>(Channel.UNLIMITED)
    private val bound = CompletableDeferred<Int>()
    private val stopRequested = CompletableDeferred<Unit>()
    private val stopped = CompletableDeferred<Unit>()
    private val worker = Worker.start(name = "test-origin")
    var port: Int = 0
        private set

    private fun runOnThisThread() {
        try {
            runReactor {
                val listener = listen("127.0.0.1", 0)
                bound.complete(listener.localAddress.port)
                val accepting = launch { acceptLoop(listener) }
                stopRequested.await()
                listener.close()
                accepting.cancel()
                coroutineContext.cancelChildren()
            }
        } catch (e: Throwable) {
            bound.completeExceptionally(e)
        } finally {
            stopped.complete(Unit)
            worker.requestTermination(processScheduledJobs = false)
        }
    }

    private suspend fun CoroutineScope.acceptLoop(listener: TcpListener) {
        val config = AutoServerConfig()
        while (true) {
            val stream = try { listener.accept() } catch (e: Exception) { return }
            launch {
                try {
                    val io: IoStream
                    val alpn: String?
                    if (tls != null) {
                        val secured = tlsAccept(stream, tls)
                        io = secured
                        alpn = secured.alpn
                    } else {
                        io = stream
                        alpn = null
                    }
                    val service = HttpService { request ->
                        val headers = ArrayList<Pair<String, String>>()
                        request.headers.forEach { name, value -> headers += name.asStr() to value.asBytes().decodeToString() }
                        var body = ByteArray(0)
                        while (true) {
                            val frame = request.body.nextFrame() ?: break
                            if (frame is StreamFrame.Data) body += frame.bytes.toByteArray()
                        }
                        val seenRequest = SeenRequest(
                            version = request.version.toString(),
                            authority = request.uri.authority?.toString(),
                            path = request.uri.pathAndQuery?.toString() ?: request.uri.toString(),
                            headers = headers,
                            body = body,
                        )
                        seen.trySend(seenRequest)
                        val (status, bytes) = respond(seenRequest)
                        StreamResponse.builder().status(status).header("content-type", "text/plain")
                            .body(StreamFullBody(Bytes.wrap(bytes)))
                    }
                    config.serveConnection(io, alpn, service).serve()
                } catch (e: Exception) {
                    // A peer that went away ends its connection only.
                } finally {
                    stream.close()
                }
            }
        }
    }

    suspend fun stop() {
        stopRequested.complete(Unit)
        stopped.await()
    }

    companion object {
        suspend fun start(
            identity: TestIdentity?,
            alpn: List<String> = listOf("h2", "http/1.1"),
            respond: suspend (SeenRequest) -> Pair<Int, ByteArray> = { 200 to "ok".encodeToByteArray() },
        ): TestOrigin {
            ignoreSigpipe()
            val tls = identity?.let {
                TlsContext(server = true, certificateChainPem = it.certificate, privateKeyPem = it.key, alpnProtocols = alpn)
            }
            val origin = TestOrigin(tls, respond)
            origin.worker.execute(TransferMode.SAFE, { origin }) { it.runOnThisThread() }
            origin.port = withTimeout(10_000) { origin.bound.await() }
            return origin
        }
    }
}

/**
 * A forward proxy that only does `CONNECT` (RFC 9110 §9.3.6), on a reactor thread of its own: records
 * each request head in [heads], connects to the requested authority, answers 200 and relays bytes both
 * ways until either side closes.
 */
@OptIn(ObsoleteWorkersApi::class)
internal class ConnectProxy private constructor() {
    val heads = Channel<String>(Channel.UNLIMITED)
    private val bound = CompletableDeferred<Int>()
    private val stopRequested = CompletableDeferred<Unit>()
    private val stopped = CompletableDeferred<Unit>()
    private val worker = Worker.start(name = "test-connect-proxy")
    var port: Int = 0
        private set

    private fun runOnThisThread() {
        try {
            runReactor {
                val listener = listen("127.0.0.1", 0)
                bound.complete(listener.localAddress.port)
                val accepting = launch {
                    while (true) {
                        val client = try { listener.accept() } catch (e: Exception) { break }
                        launch { handle(client) }
                    }
                }
                stopRequested.await()
                listener.close()
                accepting.cancel()
                coroutineContext.cancelChildren()
            }
        } catch (e: Throwable) {
            bound.completeExceptionally(e)
        } finally {
            stopped.complete(Unit)
            worker.requestTermination(processScheduledJobs = false)
        }
    }

    private suspend fun handle(client: IoStream) {
        try {
            val head = readHead(client)
            heads.trySend(head)
            val target = head.substringBefore("\r\n").split(' ')[1]
            val host = target.substringBeforeLast(':').removeSurrounding("[", "]")
            val port = target.substringAfterLast(':').toInt()
            val upstream = connect(host, port)
            try {
                writeAll(client, "HTTP/1.1 200 Connection Established\r\n\r\n".encodeToByteArray())
                coroutineScope {
                    val up = launch { pipe(client, upstream) }
                    val down = launch { pipe(upstream, client) }
                    up.join()
                    down.cancel()
                }
            } finally {
                upstream.close()
            }
        } catch (e: Exception) {
        } finally {
            client.close()
        }
    }

    private suspend fun pipe(from: IoStream, to: IoStream) {
        val buf = Buffer(16 * 1024)
        while (true) {
            if (from.read(buf) < 0) return
            while (!buf.isEmpty) to.write(buf)
            to.flush()
        }
    }

    private suspend fun readHead(stream: IoStream): String {
        val buf = Buffer(1024)
        val out = StringBuilder()
        // The client sends nothing past the head before our 200 (its TLS hello follows that).
        while (!out.endsWith("\r\n\r\n")) {
            if (stream.read(buf) < 0) error("closed during head")
            out.append(buf.readAll().decodeToString())
        }
        return out.toString()
    }

    private suspend fun writeAll(stream: IoStream, bytes: ByteArray) {
        val buf = Buffer(bytes.size)
        buf.writeBytes(bytes)
        while (!buf.isEmpty) stream.write(buf)
        stream.flush()
    }

    suspend fun stop() {
        stopRequested.complete(Unit)
        stopped.await()
    }

    companion object {
        suspend fun start(): ConnectProxy {
            ignoreSigpipe()
            val proxy = ConnectProxy()
            proxy.worker.execute(TransferMode.SAFE, { proxy }) { it.runOnThisThread() }
            proxy.port = withTimeout(10_000) { proxy.bound.await() }
            return proxy
        }
    }
}
