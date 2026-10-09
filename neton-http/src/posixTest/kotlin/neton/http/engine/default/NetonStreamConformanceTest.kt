package neton.http.engine.default

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import neton.core.http.HttpBodyWriter
import neton.core.http.adapter.HttpAdapter
import neton.core.http.adapter.HttpCapability
import neton.core.http.adapter.HttpServerConfig
import neton.http.conformance.ChunkMeter
import neton.http.conformance.ConformanceFixtures
import neton.http.conformance.ConformanceRequest
import neton.http.conformance.ConformanceResponse
import neton.http.conformance.ConformanceStream
import neton.http.conformance.HttpEngineConformanceSuite
import neton.io.bytes.Bytes
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import neton.http.Body as StreamBody
import neton.http.EmptyBody as StreamEmptyBody
import neton.http.Frame as StreamFrame
import neton.http.FullBody as StreamFullBody
import neton.http.Request as StreamRequest

/**
 * The shared engine conformance suite against the netonstream adapter, on macOS and Linux.
 *
 * The translation checks go through the adapter's own request handling (library request →
 * BufferedHttpRequest → dispatcher → library response), as the hyper4k test does with its engine
 * types. The streaming checks start the real server and read a real socket: buffered and streamed
 * only differ in when bytes reach the client.
 */
@OptIn(ExperimentalAtomicApi::class)
class NetonStreamConformanceTest : HttpEngineConformanceSuite() {

    override fun createAdapter(): HttpAdapter = DefaultHttpAdapter(HttpServerConfig(port = 0))

    override fun recordSkipped(capability: HttpCapability, testName: String) {
        // The adapter declares every capability the suite checks; a skip here means the declaration changed.
        throw AssertionError("conformance: $testName skipped, NetonStream does not declare $capability")
    }

    override suspend fun roundTrip(request: ConformanceRequest): ConformanceResponse {
        val adapter = DefaultHttpAdapter(HttpServerConfig(port = 0))
        adapter.bindContext(fixtureContext(ConformanceFixtures.routes))
        val builder = StreamRequest.builder()
            .method(request.method)
            .uri(if (request.query.isEmpty()) request.path else "${request.path}?${request.query}")
        for ((name, values) in request.headers) for (value in values) builder.header(name, value)
        val body: StreamBody = if (request.body.isEmpty()) StreamEmptyBody else StreamFullBody(Bytes.copyOf(request.body))
        val response = adapter.handle(builder.body(body))
        val headers = LinkedHashMap<String, MutableList<String>>()
        response.headers.forEach { name, value -> headers.getOrPut(name.asStr()) { mutableListOf() }.add(value.asBytes().decodeToString()) }
        var bytes = ByteArray(0)
        while (true) {
            val frame = response.body.nextFrame() ?: break
            if (frame is StreamFrame.Data) bytes += frame.bytes.toByteArray()
        }
        return ConformanceResponse(response.status.asU16(), headers, bytes)
    }

    override suspend fun streamRoundTrip(
        request: ConformanceRequest,
        produce: suspend (writer: HttpBodyWriter, meter: ChunkMeter) -> Unit,
    ): ConformanceStream {
        val delivered = AtomicInt(0)
        val server = startServer(
            listOf(
                get(request.path) { ctx ->
                    ctx.response.stream { produce(this, DeliveryMeter(delivered)) }
                    null
                },
            ),
        )
        val client = RawClient(server.port)
        return try {
            client.send(getRequest(request.path, "Connection: close\r\n"))
            // The reader runs alongside production: the meter reports what the client already holds.
            coroutineScope {
                val done = CompletableDeferred<ConformanceStream>()
                launch(Dispatchers.Default) {
                    val (status, headers) = client.readHead()
                    val chunks = mutableListOf<ByteArray>()
                    while (true) {
                        val chunk = client.nextChunk() ?: break
                        chunks += chunk
                        delivered.store(chunks.size)
                    }
                    done.complete(ConformanceStream(status, headers, chunks))
                }
                withTimeout(10_000) { done.await() }
            }
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

