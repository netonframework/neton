package neton.http.conformance

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import neton.core.http.HttpHeader
import neton.core.http.HttpHeaders
import platform.posix.AF_INET
import platform.posix.EAGAIN
import platform.posix.ECONNRESET
import platform.posix.EWOULDBLOCK
import platform.posix.MSG_DONTWAIT
import platform.posix.MSG_PEEK
import platform.posix.SHUT_RDWR
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_RCVTIMEO
import platform.posix.SO_REUSEADDR
import platform.posix.accept
import platform.posix.connect
import platform.posix.errno
import platform.posix.getsockname
import platform.posix.listen
import platform.posix.recv
import platform.posix.send
import platform.posix.setsockopt
import platform.posix.shutdown
import platform.posix.sockaddr
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.socklen_tVar
import platform.posix.timeval
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * BSD socket 实现。阻塞调用跑在 [Dispatchers.IO] 上——这是测试夹具，
 * 一个连接占一个线程完全可以接受，换成 non-blocking + poll 只会让红掉的
 * 测试更难读。
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class)
actual class ScriptedOrigin private constructor(
    private val listenFd: Int,
    private val port: Int,
    private val handle: suspend OriginConnection.(RecordedRequest) -> Unit,
) {
    actual val baseUrl: String = "http://127.0.0.1:$port"

    private val recorded = AtomicReference<List<RecordedRequest>>(emptyList())
    actual val requests: List<RecordedRequest> get() = recorded.load()

    private val stopped = AtomicInt(0)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var acceptJob: Job

    private fun run() {
        acceptJob = scope.launch { acceptLoop() }
    }

    private suspend fun acceptLoop() {
        while (stopped.load() == 0) {
            val fd = accept(listenFd, null, null)
            if (fd < 0) {
                if (stopped.load() != 0) break
                delay(5)
                continue
            }
            if (stopped.load() != 0) {
                // The wake-up connection from stop(); not a client.
                platform.posix.close(fd)
                break
            }
            scope.launch { serve(fd) }
        }
    }

    private suspend fun serve(fd: Int) {
        val conn = PosixConnection(fd)
        try {
            val request = conn.readRequest() ?: return
            recorded.store(recorded.load() + request)
            conn.handle(request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // A failing script is a test bug, not a client bug; make it visible
            // instead of letting the client see a mysterious hang-up.
            println("ScriptedOrigin: handler failed: $e")
        } finally {
            conn.closeSocket()
        }
    }

    actual suspend fun stop() {
        if (!stopped.compareAndSet(0, 1)) return
        // accept() does not reliably wake on close() everywhere, so knock on our
        // own door: the loop sees the flag, drops the connection and exits.
        shutdown(listenFd, SHUT_RDWR)
        wakeAccept(port)
        platform.posix.close(listenFd)
        withTimeoutOrNull(10_000) { acceptJob.join() }
        scope.coroutineContext[Job]?.let { job ->
            job.children.toList().forEach { child ->
                withTimeoutOrNull(10_000) { child.cancelAndJoin() }
            }
        }
        scope.cancel()
    }

    actual companion object {
        actual suspend fun start(handle: suspend OriginConnection.(RecordedRequest) -> Unit): ScriptedOrigin {
            val (fd, port) = listenOnLoopback()
            return ScriptedOrigin(fd, port, handle).also { it.run() }
        }

        actual fun unusedPort(): Int {
            val (fd, port) = listenOnLoopback()
            platform.posix.close(fd)
            return port
        }
    }
}

private const val LOOPBACK: UInt = 0x0100007Fu

private fun toNetworkOrder(port: Int): UShort =
    (((port and 0xFF) shl 8) or ((port shr 8) and 0xFF)).toUShort()

private fun fromNetworkOrder(port: UShort): Int {
    val n = port.toInt()
    return ((n and 0xFF) shl 8) or ((n shr 8) and 0xFF)
}

@OptIn(ExperimentalForeignApi::class)
private fun listenOnLoopback(): Pair<Int, Int> = memScoped {
    val fd = socket(AF_INET, SOCK_STREAM, 0)
    check(fd >= 0) { "socket() failed: errno=$errno" }
    val one = alloc<IntVar>().apply { value = 1 }
    setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, one.ptr, sizeOf<IntVar>().convert())
    val addr = alloc<sockaddr_in>()
    addr.sin_family = AF_INET.convert()
    addr.sin_addr.s_addr = LOOPBACK
    addr.sin_port = 0u
    check(platform.posix.bind(fd, addr.ptr.reinterpret<sockaddr>(), sizeOf<sockaddr_in>().convert()) == 0) {
        "bind() failed: errno=$errno"
    }
    check(listen(fd, 16) == 0) { "listen() failed: errno=$errno" }
    val length = alloc<socklen_tVar>().apply { value = sizeOf<sockaddr_in>().convert() }
    check(getsockname(fd, addr.ptr.reinterpret<sockaddr>(), length.ptr) == 0) { "getsockname() failed" }
    fd to fromNetworkOrder(addr.sin_port)
}

@OptIn(ExperimentalForeignApi::class)
private fun wakeAccept(port: Int) = memScoped {
    val fd = socket(AF_INET, SOCK_STREAM, 0)
    if (fd < 0) return@memScoped
    val addr = alloc<sockaddr_in>()
    addr.sin_family = AF_INET.convert()
    addr.sin_addr.s_addr = LOOPBACK
    addr.sin_port = toNetworkOrder(port)
    connect(fd, addr.ptr.reinterpret<sockaddr>(), sizeOf<sockaddr_in>().convert())
    platform.posix.close(fd)
}

/** One accepted connection. All I/O is blocking and runs on the IO dispatcher. */
@OptIn(ExperimentalForeignApi::class)
private class PosixConnection(private val fd: Int) : OriginConnection {
    private var closed = false
    private var buffer = ByteArray(0)

    init {
        memScoped {
            val timeout = alloc<timeval>().apply { tv_sec = 5; tv_usec = 0 }
            setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, timeout.ptr, sizeOf<timeval>().convert())
        }
        disableSigpipe(fd)
    }

    /** null when the peer went away before a full head arrived. */
    fun readRequest(): RecordedRequest? {
        val headEnd = readUntilHeadEnd() ?: return null
        val headText = buffer.copyOfRange(0, headEnd).decodeToString()
        val lines = headText.split("\r\n")
        val requestLine = lines.first()
        val parts = requestLine.split(" ")
        val headers = HttpHeaders.of(
            lines.drop(1).mapNotNull { line ->
                val i = line.indexOf(':')
                if (i <= 0) null else HttpHeader(line.substring(0, i).trim(), line.substring(i + 1).trim())
            },
        )
        buffer = buffer.copyOfRange(headEnd + 4, buffer.size)
        val body = readBody(headers) ?: return null
        return RecordedRequest(
            requestLine = requestLine,
            method = parts.getOrElse(0) { "" },
            target = parts.getOrElse(1) { "" },
            headers = headers,
            body = body,
        )
    }

    private fun readUntilHeadEnd(): Int? {
        while (true) {
            val idx = indexOfHeadEnd()
            if (idx >= 0) return idx
            val more = recvSome() ?: return null
            buffer += more
        }
    }

    private fun indexOfHeadEnd(): Int {
        var i = 0
        while (i + 3 < buffer.size) {
            if (buffer[i] == CR && buffer[i + 1] == LF && buffer[i + 2] == CR && buffer[i + 3] == LF) return i
            i++
        }
        return -1
    }

    private fun readBody(headers: HttpHeaders): ByteArray? {
        val length = headers.get("Content-Length")?.trim()?.toIntOrNull()
        if (length != null) {
            while (buffer.size < length) {
                val more = recvSome() ?: return null
                buffer += more
            }
            val body = buffer.copyOfRange(0, length)
            buffer = buffer.copyOfRange(length, buffer.size)
            return body
        }
        if (headers.get("Transfer-Encoding")?.contains("chunked", ignoreCase = true) == true) {
            return readChunkedBody()
        }
        return ByteArray(0)
    }

    private fun readChunkedBody(): ByteArray? {
        var out = ByteArray(0)
        while (true) {
            val lineEnd = indexOfCrlf(0)
            if (lineEnd < 0) {
                val more = recvSome() ?: return null
                buffer += more
                continue
            }
            val size = buffer.copyOfRange(0, lineEnd).decodeToString().substringBefore(';').trim().toIntOrNull(16)
                ?: return null
            val dataStart = lineEnd + 2
            if (size == 0) {
                // Trailer section ends with CRLF; consume through the blank line.
                while (indexOfHeadEndFrom(dataStart - 2) < 0) {
                    val more = recvSome() ?: return out
                    buffer += more
                }
                return out
            }
            while (buffer.size < dataStart + size + 2) {
                val more = recvSome() ?: return null
                buffer += more
            }
            out += buffer.copyOfRange(dataStart, dataStart + size)
            buffer = buffer.copyOfRange(dataStart + size + 2, buffer.size)
        }
    }

    private fun indexOfCrlf(from: Int): Int {
        var i = from
        while (i + 1 < buffer.size) {
            if (buffer[i] == CR && buffer[i + 1] == LF) return i
            i++
        }
        return -1
    }

    private fun indexOfHeadEndFrom(from: Int): Int {
        var i = maxOf(from, 0)
        while (i + 3 < buffer.size) {
            if (buffer[i] == CR && buffer[i + 1] == LF && buffer[i + 2] == CR && buffer[i + 3] == LF) return i
            i++
        }
        return -1
    }

    private fun recvSome(): ByteArray? {
        val chunk = ByteArray(8192)
        val n = chunk.usePinned { pinned -> recv(fd, pinned.addressOf(0), chunk.size.convert(), 0).toInt() }
        return if (n > 0) chunk.copyOfRange(0, n) else null
    }

    override suspend fun writeFixed(status: Int, headers: HttpHeaders, body: ByteArray) {
        val head = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n")
            headers.asList().forEach { append(it.name).append(": ").append(it.value).append("\r\n") }
            append("Content-Length: ").append(body.size).append("\r\n")
            append("Connection: close\r\n\r\n")
        }
        sendAll(head.encodeToByteArray() + body)
    }

    override suspend fun writeHead(status: Int, headers: HttpHeaders) {
        val head = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n")
            headers.asList().forEach { append(it.name).append(": ").append(it.value).append("\r\n") }
            append("Transfer-Encoding: chunked\r\n")
            append("Connection: close\r\n\r\n")
        }
        sendAll(head.encodeToByteArray())
    }

    override suspend fun writeChunk(bytes: ByteArray) {
        sendAll(bytes.size.toString(16).encodeToByteArray() + CRLF + bytes + CRLF)
    }

    override suspend fun end() {
        sendAll("0\r\n\r\n".encodeToByteArray())
    }

    override suspend fun abort() {
        closeSocket()
    }

    override suspend fun awaitPeerClosed(timeoutMillis: Long): Boolean {
        val result = withTimeoutOrNull(timeoutMillis) {
            while (true) {
                if (peerHasClosed()) return@withTimeoutOrNull true
                delay(10)
            }
            @Suppress("UNREACHABLE_CODE")
            false
        }
        return result == true
    }

    private fun peerHasClosed(): Boolean {
        val probe = ByteArray(1)
        val n = probe.usePinned { pinned ->
            recv(fd, pinned.addressOf(0), 1.convert(), MSG_PEEK or MSG_DONTWAIT).toInt()
        }
        if (n == 0) return true
        if (n > 0) return false
        val err = errno
        return err != EAGAIN && err != EWOULDBLOCK && err == ECONNRESET
    }

    private fun sendAll(bytes: ByteArray) {
        if (closed) return
        bytes.usePinned { pinned ->
            var sent = 0
            while (sent < bytes.size) {
                val n = send(fd, pinned.addressOf(sent), (bytes.size - sent).convert(), sendFlags()).toInt()
                if (n <= 0) {
                    // Peer went away mid-write. That is a legitimate outcome for the
                    // cancellation test; the script decides whether it matters.
                    return
                }
                sent += n
            }
        }
    }

    fun closeSocket() {
        if (!closed) {
            closed = true
            platform.posix.close(fd)
        }
    }

    private fun reason(status: Int): String = when (status) {
        200 -> "OK"
        201 -> "Created"
        204 -> "No Content"
        400 -> "Bad Request"
        404 -> "Not Found"
        429 -> "Too Many Requests"
        500 -> "Internal Server Error"
        503 -> "Service Unavailable"
        else -> "Status"
    }

    private companion object {
        const val CR: Byte = 0x0D
        const val LF: Byte = 0x0A
        val CRLF: ByteArray = byteArrayOf(CR, LF)
    }
}

/**
 * SIGPIPE 的处理是 macOS 与 Linux 唯一分叉的地方：前者用 socket 选项，后者用
 * send 标志。测试进程被一个已断开的对端用信号杀掉，是最难排查的一种红。
 */
internal expect fun disableSigpipe(fd: Int)
internal expect fun sendFlags(): Int
