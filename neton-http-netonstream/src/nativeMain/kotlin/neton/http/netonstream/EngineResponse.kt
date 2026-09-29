package neton.http.netonstream

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import neton.io.bytes.Bytes
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
// The library and the framework share the package name `neton.http` (see the module README): the
// library's message types are imported under engine-specific names so no simple name is ambiguous.
import neton.http.Body as StreamBody
import neton.http.EmptyBody as StreamEmptyBody
import neton.http.Frame as StreamFrame
import neton.http.FullBody as StreamFullBody
import neton.http.Response as StreamResponse
import neton.http.ResponseParts as StreamResponseParts
import neton.http.StatusCode as StreamStatusCode
import neton.http.header.HeaderName as StreamHeaderName
import neton.http.header.HeaderValue as StreamHeaderValue

/**
 * What the adapter answers with, before it becomes a library [StreamResponse]: a status, headers as
 * the framework models them, and either a complete [body] or a live [stream].
 *
 * Kept separate from the library type so the gzip policy and the translation can be tested as
 * plain values.
 */
internal class EngineResponse(
    val status: Int,
    val headers: Map<String, List<String>>,
    val body: ByteArray,
    val stream: LiveBody? = null,
) {
    val isStreamed: Boolean get() = stream != null
}

/**
 * Builds the library response.
 *
 * [connectionJob] is the job of the coroutine serving this exchange (HTTP/1: the connection's
 * exchange job; HTTP/2: the stream's coroutine). Its completion before a streamed body has ended
 * means the client went away (closed the connection, or reset the stream): the body is then marked
 * gone, which ends the handler's writes.
 *
 * Header names or values the library rejects (control characters, invalid tokens) are dropped, as
 * hyper4k's header block parser does.
 */
internal fun EngineResponse.toStreamResponse(connectionJob: Job?): StreamResponse<out StreamBody> {
    val parts = StreamResponseParts(status = StreamStatusCode.tryFromU16(status) ?: StreamStatusCode.INTERNAL_SERVER_ERROR)
    if (headers.isNotEmpty()) {
        val map = parts.headers
        for ((name, values) in headers) {
            // The connection frames the body: a Content-Length of the application's could only contradict it.
            if (name.equals("Content-Length", ignoreCase = true)) continue
            val headerName = StreamHeaderName.tryFromStr(name) ?: continue
            for (value in values) {
                val headerValue = StreamHeaderValue.tryFromStr(value) ?: continue
                map.append(headerName, headerValue)
            }
        }
    }
    val live = stream
    val body: StreamBody = when {
        live != null -> live.also { it.watch(connectionJob) }
        body.isEmpty() -> StreamEmptyBody
        else -> StreamFullBody(Bytes.wrap(body))
    }
    return StreamResponse(parts, body)
}

/**
 * The body of a streamed response: a bounded queue between the handler (producer, on the handler's
 * dispatcher) and the connection (consumer, on its reactor).
 *
 * - Backpressure: [send] suspends while [capacity] chunks are queued; the connection takes the next
 *   one only after writing the previous one to the socket.
 * - Client gone: the connection's job completing, or its read of the next frame being cancelled,
 *   marks the body gone and cancels the queue; a producer blocked in [send] wakes up, and [send]
 *   returns false from then on.
 * - End: [finish] closes the queue; chunks already queued are still written, then the body ends.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class LiveBody(capacity: Int) : StreamBody {
    private val channel = Channel<ByteArray>(capacity)
    private val gone = AtomicBoolean(false)
    private val producer = AtomicReference<Job?>(null)
    private var watchHandle: DisposableHandle? = null

    val isGone: Boolean get() = gone.load()

    fun bindProducer(job: Job) {
        producer.store(job)
        if (gone.load()) job.cancel()
        job.invokeOnCompletion { producer.compareAndSet(job, null) }
    }

    /** Ties the body to the exchange's lifetime; the handle is dropped once the body has ended. */
    fun watch(job: Job?) {
        if (job == null) return
        watchHandle = job.invokeOnCompletion { markGone() }
    }

    fun markGone() {
        if (gone.compareAndSet(false, true)) {
            channel.cancel()
            producer.load()?.cancel()
        }
    }

    override suspend fun nextFrame(): StreamFrame? {
        val result = try {
            channel.receiveCatching()
        } catch (e: CancellationException) {
            // The connection stopped pulling: its client is gone or it is shutting down.
            markGone()
            throw e
        }
        val chunk = result.getOrNull()
        if (chunk == null) {
            watchHandle?.dispose()
            watchHandle = null
            return null
        }
        return StreamFrame.Data(Bytes.wrap(chunk))
    }

    /** Queues [chunk]; false when the client is gone (the chunk is dropped). */
    suspend fun send(chunk: ByteArray): Boolean {
        if (gone.load()) return false
        return try {
            channel.send(chunk)
            true
        } catch (e: ClosedSendChannelException) {
            false
        } catch (e: CancellationException) {
            // The queue was cancelled because the client left: not an error for the handler. Its own
            // cancellation (timeout, shutdown) still propagates.
            if (gone.load() && currentCoroutineContext().isActive) false else throw e
        }
    }

    fun finish() {
        channel.close()
    }
}
