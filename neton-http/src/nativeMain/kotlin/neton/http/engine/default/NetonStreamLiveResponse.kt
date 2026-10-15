package neton.http.engine.default

import neton.core.http.Cookie
import neton.core.http.HttpBodyWriter
import neton.core.http.HttpException
import neton.core.http.HttpResponse
import neton.core.http.HttpStatus
import neton.core.http.MutableHeaders
import neton.core.http.NetonErrorCode

/**
 * Streaming [HttpResponse]: the handler's chunks go straight into the connection's response body,
 * which is what SSE and relay endpoints need.
 *
 * Same semantics as the hyper4k and Ktor live responses: every commit entry point (write / stream /
 * redirect) sets [isCommitted], and committing twice is refused. A complete body is not streamed:
 * it is held here and handed back as one [EngineResponse], so a plain `response.text("ok")` costs
 * no channel and no coroutine switch.
 *
 * A client that goes away cancels the producer task, including a blocked write. Handlers must
 * release their resources in finally; [clientGone] also records the abandoned body.
 */
internal class NetonStreamLiveResponse(
    private val corsHeaders: Map<String, List<String>>,
    private val channelCapacity: Int,
    /** Hands the response head and its live body to the connection; called once, when [stream] starts. */
    private val onStreamStart: (EngineResponse) -> Unit,
) : HttpResponse {

    private val responseHeaders = SimpleMutableHeaders()
    override val headers: MutableHeaders get() = responseHeaders

    override var status: HttpStatus = HttpStatus.OK

    /** Each cookie is one more Set-Cookie field; they are never folded into one line. */
    override fun cookie(cookie: Cookie) {
        headers.add("Set-Cookie", encodeCookie(cookie))
    }

    private var committed = false
    override val isCommitted: Boolean get() = committed

    private var streaming = false

    /** True only when the handler actually streamed: its head is already with the connection. */
    val isStreaming: Boolean get() = streaming

    private var completeBody: ByteArray? = null

    /** The buffered answer of a handler that wrote a complete body. Valid once [isCommitted] and not [isStreaming]. */
    fun completeResponse(): EngineResponse =
        EngineResponse(status.code, outgoingHeaders(), completeBody ?: ByteArray(0))

    private var writtenBytes: Long = 0L
    override val bytesOut: Long get() = writtenBytes

    private var body: LiveBody? = null

    /** Whether the client went away while the handler was streaming. */
    val clientGone: Boolean get() = body?.isGone == true

    private fun ensureNotCommitted() {
        if (committed) throw HttpException(
            NetonErrorCode.INTERNAL_ERROR,
            "Response already committed (ResponseAlreadyCommitted)",
        )
    }

    /**
     * The headers to send, CORS merged in (it must be there before the head goes out).
     *
     * Content-Length is always dropped: the connection frames the body itself (HTTP/1.1 chunked or
     * a known length, HTTP/2 DATA frames), so a hand-written one can only contradict it.
     */
    private fun outgoingHeaders(): Map<String, List<String>> {
        if (corsHeaders.isEmpty()) return responseHeaders.snapshotWithoutContentLength()
        return buildMap<String, MutableList<String>> {
            for (name in headers.names()) {
                if (name.equals("Content-Length", ignoreCase = true)) continue
                getOrPut(name) { mutableListOf() }.addAll(headers.getAll(name))
            }
            for ((name, values) in corsHeaders) {
                getOrPut(name) { mutableListOf() }.addAll(values)
            }
            if (none { it.key.equals("Content-Type", ignoreCase = true) }) {
                contentType?.let { put("Content-Type", mutableListOf(it)) }
            }
        }
    }

    override suspend fun write(data: ByteArray) {
        ensureNotCommitted()
        committed = true
        completeBody = data
        writtenBytes = data.size.toLong()
    }

    /**
     * Streaming write: the head goes to the connection at once, then each `writeChunk` is queued
     * on the response body and written as soon as the connection takes it.
     *
     * The queue is bounded ([channelCapacity] chunks), so a slow reader makes `writeChunk` wait
     * instead of piling the response up in memory: the connection only takes the next chunk once
     * the previous one has been handed to the socket.
     */
    override suspend fun stream(block: suspend HttpBodyWriter.() -> Unit) {
        ensureNotCommitted()
        committed = true
        streaming = true
        val live = LiveBody(channelCapacity)
        body = live
        onStreamStart(EngineResponse(status.code, outgoingHeaders(), ByteArray(0), live))
        val writer = object : HttpBodyWriter {
            override suspend fun writeChunk(chunk: ByteArray) {
                if (chunk.isEmpty()) return
                // A copy: the connection writes it later, and the caller owns its array again as soon as this returns.
                if (live.send(chunk.copyOf())) writtenBytes += chunk.size
            }
        }
        try {
            writer.block()
        } finally {
            live.finish()
        }
    }

    override suspend fun redirect(url: String, status: HttpStatus) {
        ensureNotCommitted()
        this.status = status
        header("Location", url)
        committed = true
        completeBody = ByteArray(0)
    }
}

internal fun encodeCookie(cookie: Cookie): String = buildString {
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

/**
 * Small MutableHeaders: keeps the original casing, looks up case-insensitively.
 *
 * [toMap] is a read-only snapshot kept until the next change: the framework takes one after every
 * dispatch, and the head the connection sends is usually that same snapshot, so one copy serves both.
 */
private class SimpleMutableHeaders : MutableHeaders {
    // Most buffered responses have just Content-Type. Allocate the general representation only
    // for another name or a repeated value; each response still owns all of its mutable state.
    private var singleName: String? = null
    private var singleValue: String? = null
    private var map: LinkedHashMap<String, MutableList<String>>? = null
    private var snapshot: Map<String, List<String>>? = null

    private fun actualName(name: String): String? =
        map?.keys?.firstOrNull { it.equals(name, ignoreCase = true) }
            ?: singleName?.takeIf { it.equals(name, ignoreCase = true) }

    private fun promote(): LinkedHashMap<String, MutableList<String>> {
        map?.let { return it }
        return LinkedHashMap<String, MutableList<String>>().also { expanded ->
            singleName?.let { expanded[it] = mutableListOf(singleValue!!) }
            singleName = null
            singleValue = null
            map = expanded
        }
    }

    override fun get(name: String): String? {
        val actual = actualName(name) ?: return null
        return map?.get(actual)?.firstOrNull() ?: singleValue
    }
    override fun getAll(name: String): List<String> {
        val actual = actualName(name) ?: return emptyList()
        return map?.get(actual) ?: listOf(singleValue!!)
    }
    override fun contains(name: String): Boolean = actualName(name) != null
    override fun names(): Set<String> = map?.keys ?: singleName?.let { setOf(it) } ?: emptySet()
    override fun toMap(): Map<String, List<String>> {
        snapshot?.let { return it }
        val current = map
        val name = singleName
        return when {
            current != null -> if (current.isEmpty()) emptyMap() else current.mapValues { it.value.toList() }
            name != null -> mapOf(name to listOf(singleValue!!))
            else -> emptyMap()
        }.also { snapshot = it }
    }

    /** The snapshot without Content-Length (the connection frames the body); the [toMap] snapshot itself when there is none. */
    fun snapshotWithoutContentLength(): Map<String, List<String>> {
        if (!contains("Content-Length")) return toMap()
        val current = map ?: return emptyMap()
        return buildMap(current.size) {
            for ((name, values) in current) {
                if (!name.equals("Content-Length", ignoreCase = true)) put(name, values.toList())
            }
        }
    }

    override fun set(name: String, value: String) {
        if (map == null && (singleName == null || singleName.equals(name, ignoreCase = true))) {
            singleName = name
            singleValue = value
        } else {
            val current = promote()
            actualName(name)?.let(current::remove)
            current[name] = mutableListOf(value)
        }
        snapshot = null
    }

    override fun add(name: String, value: String) {
        if (map == null && singleName == null) {
            singleName = name
            singleValue = value
        } else {
            val current = promote()
            current.getOrPut(actualName(name) ?: name) { mutableListOf() }.add(value)
        }
        snapshot = null
    }

    override fun remove(name: String) {
        val actual = actualName(name) ?: return
        val current = map
        if (current != null) current.remove(actual) else {
            singleName = null
            singleValue = null
        }
        snapshot = null
    }

    override fun clear() {
        map = null
        singleName = null
        singleValue = null
        snapshot = null
    }
}
