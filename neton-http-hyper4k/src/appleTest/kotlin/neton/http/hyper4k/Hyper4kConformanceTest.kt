package neton.http.hyper4k

import neton.core.http.adapter.HttpServerConfig

import hyper4k.Hyper4kResponse
import hyper4k.Hyper4kServer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import neton.core.component.NetonContext
import neton.core.http.HttpBodyWriter
import neton.core.http.adapter.HttpAdapter
import neton.core.http.adapter.HttpCapability
import neton.core.interfaces.ConfiguredRouteGroups
import neton.core.interfaces.RequestEngine
import neton.core.interfaces.RouteDefinition
import neton.http.conformance.ChunkMeter
import neton.http.conformance.ConformanceFixtures
import neton.http.conformance.ConformanceRequest
import neton.http.conformance.ConformanceResponse
import neton.http.conformance.ConformanceStream
import neton.http.conformance.HttpEngineConformanceSuite
import platform.posix.AF_INET
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_RCVTIMEO
import platform.posix.close
import platform.posix.connect
import platform.posix.getsockname
import platform.posix.recv
import platform.posix.send
import platform.posix.setsockopt
import platform.posix.sockaddr
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.socklen_tVar
import platform.posix.timeval
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test

/**
 * Runs the shared engine conformance suite against hyper4k.
 *
 * The translation-layer checks go through the adapter's own dispatch. The
 * streaming checks stand up a real server and read from a real socket, because
 * "buffered" and "streamed" only differ in when bytes reach the client.
 *
 * Apple targets only: the client below talks BSD sockets directly.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class)
class Hyper4kConformanceTest : HttpEngineConformanceSuite() {

    private val skipped = mutableListOf<String>()

    override fun createAdapter(): HttpAdapter =
        Hyper4kHttpAdapter(HttpServerConfig(port = 0))

    override fun recordSkipped(capability: HttpCapability, testName: String) {
        skipped += "$capability/$testName"
        println("conformance: skipped $testName, hyper4k does not declare $capability")
    }

    override suspend fun roundTrip(request: ConformanceRequest): ConformanceResponse {
        val adapter = Hyper4kHttpAdapter(HttpServerConfig(port = 0))
        adapter.bindContext(fixtureContext())
        val response = adapter.dispatch(request.toHyper4k())
        return ConformanceResponse(
            status = response.status,
            headers = response.headers,
            body = response.body,
        )
    }

    override suspend fun streamRoundTrip(
        request: ConformanceRequest,
        produce: suspend (writer: HttpBodyWriter, meter: ChunkMeter) -> Unit,
    ): ConformanceStream {
        val port = freePort()
        val received = ChunkedReader()
        val delivered = AtomicInt(0)
        val done = CompletableDeferred<Unit>()

        val server = Hyper4kServer(host = "127.0.0.1", port = port)
        server.start { _, channel ->
            val live = Hyper4kLiveResponse(channel, corsHeaders = emptyMap())
            live.stream { produce(this, DeliveryMeter(delivered)) }
            Hyper4kResponse.streamed(live.status.code)
        }

        val client = Socket(port)
        return try {
            client.send("GET ${request.path} HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
            // The reader has to run alongside production: the meter reports what the
            // client already holds, and that is the whole point of the observation.
            coroutineScope {
                launch(Dispatchers.Default) {
                    while (!received.isComplete) {
                        val more = client.receive() ?: break
                        received.feed(more)
                        delivered.store(received.chunks.size)
                    }
                    done.complete(Unit)
                }
                withTimeout(10_000) { done.await() }
            }
            ConformanceStream(
                status = received.status,
                headers = received.headers,
                chunks = received.chunks,
            )
        } finally {
            client.close()
            server.stop()
        }
    }

    @Test
    fun repeatedRequestHeadersSurvive() = runBlocking { checkRepeatedRequestHeadersSurvive() }

    @Test
    fun queryIsSplitFromPath() = runBlocking { checkQueryIsSplitFromPath() }

    @Test
    fun nonUtf8BodyBytesSurvive() = runBlocking { checkNonUtf8BodyBytesSurvive() }

    @Test
    fun emptyBodyIsEmptyNotNull() = runBlocking { checkEmptyBodyIsEmptyNotNull() }

    @Test
    fun streamingReleasesChunksAsProduced() = runBlocking { checkStreamingReleasesChunksAsProduced() }

    @Test
    fun streamingDoesNotDeclareContentLength() = runBlocking { checkStreamingDoesNotDeclareContentLength() }

    private fun fixtureContext() = NetonContext(emptyArray()).apply {
        bind(RequestEngine::class, FixtureRequestEngine(ConformanceFixtures.routes))
        bind(ConfiguredRouteGroups(emptySet()))
    }
}

private fun ConformanceRequest.toHyper4k() = hyper4k.Hyper4kRequest(
    method = method,
    path = path,
    query = query,
    rawHeaders = headers.entries
        .flatMap { (name, values) -> values.map { "$name: $it" } }
        .joinToString("\n"),
    body = body,
)

private class FixtureRequestEngine(private val routes: List<RouteDefinition>) : RequestEngine {
    override fun registerRoute(route: RouteDefinition) = Unit
    override fun getRoutes(): List<RouteDefinition> = routes
}

/**
 * Parses an HTTP/1.1 response head plus chunked body.
 *
 * Counting recv() calls would be simpler but flaky: two chunks can arrive in one
 * read. Chunk framing is what the transport actually promised, so parse that.
 */
private class ChunkedReader {
    private var buffer = ByteArray(0)
    private var headParsed = false
    private var bodyStart = 0

    var status: Int = 0
        private set
    var headers: Map<String, List<String>> = emptyMap()
        private set
    val chunks: MutableList<ByteArray> = mutableListOf()
    var isComplete: Boolean = false
        private set

    fun feed(more: ByteArray) {
        buffer += more
        if (!headParsed) parseHead()
        if (headParsed) parseChunks()
    }

    private fun parseHead() {
        val text = buffer.decodeToString()
        val end = text.indexOf("\r\n\r\n")
        if (end < 0) return
        val lines = text.substring(0, end).split("\r\n")
        status = lines.first().split(" ").getOrNull(1)?.toIntOrNull() ?: 0
        headers = buildMap<String, MutableList<String>> {
            for (line in lines.drop(1)) {
                val i = line.indexOf(':')
                if (i <= 0) continue
                getOrPut(line.substring(0, i).trim()) { mutableListOf() }.add(line.substring(i + 1).trim())
            }
        }
        bodyStart = end + 4
        headParsed = true
    }

    private fun parseChunks() {
        while (true) {
            val rest = buffer.copyOfRange(bodyStart, buffer.size)
            val text = rest.decodeToString()
            val lineEnd = text.indexOf("\r\n")
            if (lineEnd < 0) return
            val size = text.substring(0, lineEnd).substringBefore(';').trim().toIntOrNull(16) ?: return
            val dataStart = lineEnd + 2
            if (rest.size < dataStart + size + 2) return
            if (size == 0) {
                isComplete = true
                return
            }
            chunks += rest.copyOfRange(dataStart, dataStart + size)
            bodyStart += dataStart + size + 2
        }
    }
}

private const val LOOPBACK: UInt = 0x0100007Fu

@OptIn(ExperimentalForeignApi::class)
private fun freePort(): Int = memScoped {
    val fd = socket(AF_INET, SOCK_STREAM, 0)
    check(fd >= 0) { "socket() failed" }
    try {
        val addr = alloc<sockaddr_in>()
        addr.sin_family = AF_INET.convert()
        addr.sin_addr.s_addr = LOOPBACK
        addr.sin_port = 0u
        check(platform.posix.bind(fd, addr.ptr.reinterpret<sockaddr>(), sizeOf<sockaddr_in>().convert()) == 0) {
            "bind() failed"
        }
        val length = alloc<socklen_tVar>()
        length.value = sizeOf<sockaddr_in>().convert()
        check(getsockname(fd, addr.ptr.reinterpret<sockaddr>(), length.ptr) == 0) { "getsockname() failed" }
        val networkOrder = addr.sin_port.toInt()
        ((networkOrder and 0xFF) shl 8) or ((networkOrder shr 8) and 0xFF)
    } finally {
        close(fd)
    }
}

@OptIn(ExperimentalForeignApi::class)
private class Socket(port: Int) {
    private val fd = socket(AF_INET, SOCK_STREAM, 0)
    private var isClosed = false

    init {
        check(fd >= 0) { "socket() failed" }
        memScoped {
            val addr = alloc<sockaddr_in>()
            addr.sin_family = AF_INET.convert()
            addr.sin_addr.s_addr = LOOPBACK
            addr.sin_port = (((port and 0xFF) shl 8) or ((port shr 8) and 0xFF)).convert()
            check(connect(fd, addr.ptr.reinterpret<sockaddr>(), sizeOf<sockaddr_in>().convert()) == 0) {
                "connect() to 127.0.0.1:$port failed"
            }
            val timeout = alloc<timeval>()
            timeout.tv_sec = 10
            timeout.tv_usec = 0
            setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, timeout.ptr, sizeOf<timeval>().convert())
        }
    }

    fun send(text: String) {
        val bytes = text.encodeToByteArray()
        bytes.usePinned { pinned ->
            var sent = 0
            while (sent < bytes.size) {
                val n = send(fd, pinned.addressOf(sent), (bytes.size - sent).convert(), 0).toInt()
                check(n > 0) { "send() failed" }
                sent += n
            }
        }
    }

    /** One read. null once the peer closed or the read timed out. */
    fun receive(): ByteArray? {
        val buffer = ByteArray(4096)
        val n = buffer.usePinned { pinned ->
            recv(fd, pinned.addressOf(0), buffer.size.convert(), 0).toInt()
        }
        return if (n > 0) buffer.copyOfRange(0, n) else null
    }

    fun close() {
        if (!isClosed) {
            isClosed = true
            close(fd)
        }
    }
}

/** Reports what the socket reader has actually parsed off the wire. */
@OptIn(ExperimentalAtomicApi::class)
private class DeliveryMeter(private val delivered: AtomicInt) : ChunkMeter {
    override fun released(): Int = delivered.load()

    override suspend fun awaitReleased(count: Int, timeoutMillis: Long): Boolean =
        withTimeoutOrNull(timeoutMillis) {
            while (delivered.load() < count) delay(2)
            true
        } ?: false
}
