package neton.http.hyper4k

import hyper4k.Hyper4kResponseChannel
import neton.core.http.Cookie
import neton.core.http.HttpBodyWriter
import neton.core.http.HttpException
import neton.core.http.HttpResponse
import neton.core.http.HttpStatus
import neton.core.http.MutableHeaders
import neton.core.http.NetonErrorCode

/**
 * Streaming [HttpResponse]: the handler writes straight to hyper4k's downstream
 * channel, which is what SSE and relay endpoints need.
 *
 * Mirrors KtorLiveResponse, semantics included: every commit entry point
 * (write / stream / redirect) sets [isCommitted], and writing twice is refused.
 * The adapter reads that flag to tell whether the handler already answered.
 *
 * A client disconnecting midway is not an error: when
 * [Hyper4kResponseChannel.write] returns false, stop writing and close. That is
 * the path an SSE client takes when it closes its tab.
 */
internal class Hyper4kLiveResponse(
    private val channel: Hyper4kResponseChannel,
    private val corsHeaders: Map<String, List<String>>,
) : HttpResponse {

    override val headers: MutableHeaders = SimpleMutableHeaders()

    override var status: HttpStatus = HttpStatus.OK

    /** hyper4k's header block is flat text, so Set-Cookie goes out as one more line. */
    override fun cookie(cookie: Cookie) {
        headers.add("Set-Cookie", encodeCookie(cookie))
    }

    private var committed = false
    override val isCommitted: Boolean get() = committed

    private var writtenBytes: Long = 0L
    override val bytesOut: Long get() = writtenBytes

    /** Whether the client is gone, that is a write reported CLIENT_GONE. */
    var clientGone: Boolean = false
        private set

    private fun ensureNotCommitted() {
        if (committed) throw HttpException(
            NetonErrorCode.INTERNAL_ERROR,
            "Response already committed (ResponseAlreadyCommitted)",
        )
    }

    /**
     * Collects the headers to send.
     *
     * Content-Length is always dropped: the engine expresses a streaming body's
     * length per protocol, as HTTP/1.1 chunked or HTTP/2 DATA frames, so a
     * hand-written one can only contradict the real length.
     */
    private fun outgoingHeaders(): Map<String, List<String>> = buildMap<String, MutableList<String>> {
        for (name in headers.names()) {
            if (name.equals("Content-Length", ignoreCase = true)) continue
            getOrPut(name) { mutableListOf() }.addAll(headers.getAll(name))
        }
        // CORS headers have to go in before committing: once the body starts, no
        // header can be added.
        for ((name, values) in corsHeaders) {
            getOrPut(name) { mutableListOf() }.addAll(values)
        }
        if (none { it.key.equals("Content-Type", ignoreCase = true) }) {
            contentType?.let { put("Content-Type", mutableListOf(it)) }
        }
    }

    override suspend fun write(data: ByteArray) {
        ensureNotCommitted()
        committed = true
        channel.begin(status.code, outgoingHeaders())
        if (data.isNotEmpty() && !channel.write(data)) {
            clientGone = true
        }
        writtenBytes = channel.bytesWritten
        channel.finish()
    }

    /**
     * Streaming write: each writeChunk lands in the engine's body channel and goes
     * out at once.
     *
     * The channel itself carries the backpressure, so writeChunk waits while the
     * client reads slowly rather than piling the whole response up in memory.
     */
    override suspend fun stream(block: suspend HttpBodyWriter.() -> Unit) {
        ensureNotCommitted()
        committed = true
        channel.begin(status.code, outgoingHeaders())
        val writer = object : HttpBodyWriter {
            override suspend fun writeChunk(chunk: ByteArray) {
                if (clientGone) return
                if (!channel.write(chunk)) clientGone = true
            }
        }
        try {
            writer.block()
        } finally {
            writtenBytes = channel.bytesWritten
            channel.finish()
        }
    }

    override suspend fun redirect(url: String, status: HttpStatus) {
        ensureNotCommitted()
        this.status = status
        header("Location", url)
        committed = true
        channel.begin(status.code, outgoingHeaders())
        channel.finish()
    }
}

private fun encodeCookie(cookie: Cookie): String = buildString {
    append(cookie.name).append('=').append(cookie.value)
    cookie.path?.let { append("; Path=").append(it) }
    cookie.domain?.let { append("; Domain=").append(it) }
    cookie.maxAge?.let { append("; Max-Age=").append(it) }
    if (cookie.secure) append("; Secure")
    if (cookie.httpOnly) append("; HttpOnly")
    cookie.sameSite?.let {
        append("; SameSite=").append(it.name.lowercase().replaceFirstChar(Char::uppercase))
    }
}

/** Small MutableHeaders: keeps the original casing, looks up case-insensitively. */
private class SimpleMutableHeaders : MutableHeaders {
    private val map = LinkedHashMap<String, MutableList<String>>()

    private fun actualName(name: String): String? = map.keys.firstOrNull { it.equals(name, ignoreCase = true) }

    override fun get(name: String): String? = actualName(name)?.let { map[it]?.firstOrNull() }
    override fun getAll(name: String): List<String> = actualName(name)?.let { map[it] } ?: emptyList()
    override fun contains(name: String): Boolean = actualName(name) != null
    override fun names(): Set<String> = map.keys
    override fun toMap(): Map<String, List<String>> = map.mapValues { it.value.toList() }

    override fun set(name: String, value: String) {
        actualName(name)?.let(map::remove)
        map[name] = mutableListOf(value)
    }

    override fun add(name: String, value: String) {
        map.getOrPut(actualName(name) ?: name) { mutableListOf() }.add(value)
    }

    override fun remove(name: String) {
        actualName(name)?.let(map::remove)
    }

    override fun clear() = map.clear()
}
