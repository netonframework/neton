package neton.http.conformance

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.runBlocking
import neton.core.http.HttpHeaders
import platform.posix.AF_INET
import platform.posix.SOCK_STREAM
import platform.posix.close
import platform.posix.connect
import platform.posix.recv
import platform.posix.send
import platform.posix.sockaddr
import platform.posix.sockaddr_in
import platform.posix.socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The origin is the suite's measuring instrument, so it gets checked without any
 * engine in the loop: a raw socket speaks HTTP/1.1 to it by hand.
 */
@OptIn(ExperimentalForeignApi::class)
class ScriptedOriginTest {

    @Test
    fun servesAFixedResponseAndRecordsTheRequest() = runBlocking {
        val origin = ScriptedOrigin.start { request ->
            writeFixed(201, HttpHeaders.of("X-Echo" to request.target), request.body)
        }
        try {
            val port = origin.baseUrl.substringAfterLast(':').toInt()
            val reply = RawSocket(port).use { s ->
                s.send("POST /things?x=1 HTTP/1.1\r\nHost: t\r\nContent-Length: 5\r\n\r\nhello")
                s.readAll()
            }
            assertTrue(reply.startsWith("HTTP/1.1 201 Created\r\n"), reply)
            assertTrue("X-Echo: /things?x=1\r\n" in reply, reply)
            assertTrue(reply.endsWith("\r\n\r\nhello"), reply)
            val seen = origin.requests.single()
            assertEquals("POST", seen.method)
            assertEquals("hello", seen.body.decodeToString())
            assertEquals("t", seen.headers.get("host"))
        } finally {
            origin.stop()
        }
    }

    @Test
    fun streamsChunksWithChunkedFraming() = runBlocking {
        val origin = ScriptedOrigin.start {
            writeHead(200)
            writeChunk("ab".encodeToByteArray())
            writeChunk("cde".encodeToByteArray())
            end()
        }
        try {
            val port = origin.baseUrl.substringAfterLast(':').toInt()
            val reply = RawSocket(port).use { s ->
                s.send("GET / HTTP/1.1\r\nHost: t\r\n\r\n")
                s.readAll()
            }
            assertTrue("Transfer-Encoding: chunked\r\n" in reply, reply)
            assertTrue(reply.endsWith("\r\n\r\n2\r\nab\r\n3\r\ncde\r\n0\r\n\r\n"), reply)
        } finally {
            origin.stop()
        }
    }

    @Test
    fun unusedPortRefusesConnections() {
        val port = ScriptedOrigin.unusedPort()
        assertTrue(RawSocket.tryConnect(port) < 0, "port $port should refuse")
    }

    @Test
    fun stopIsIdempotentAndReleasesThePort() = runBlocking {
        val origin = ScriptedOrigin.start { writeFixed(200) }
        val port = origin.baseUrl.substringAfterLast(':').toInt()
        origin.stop()
        origin.stop()
        assertTrue(RawSocket.tryConnect(port) < 0, "port $port should be closed after stop()")
    }
}

private const val LOOPBACK: UInt = 0x0100007Fu

@OptIn(ExperimentalForeignApi::class)
private class RawSocket(port: Int) {
    private val fd = socket(AF_INET, SOCK_STREAM, 0)

    init {
        check(fd >= 0)
        check(RawSocket.connectTo(fd, port) == 0) { "connect to $port failed" }
    }

    fun send(text: String) {
        val bytes = text.encodeToByteArray()
        bytes.usePinned { p ->
            var sent = 0
            while (sent < bytes.size) {
                val n = send(fd, p.addressOf(sent), (bytes.size - sent).convert(), 0).toInt()
                check(n > 0); sent += n
            }
        }
    }

    fun readAll(): String {
        var out = ByteArray(0)
        val buf = ByteArray(4096)
        while (true) {
            val n = buf.usePinned { p -> recv(fd, p.addressOf(0), buf.size.convert(), 0).toInt() }
            if (n <= 0) break
            out += buf.copyOfRange(0, n)
        }
        return out.decodeToString()
    }

    inline fun <T> use(block: (RawSocket) -> T): T = try { block(this) } finally { close(fd) }

    companion object {
        fun tryConnect(port: Int): Int {
            val fd = socket(AF_INET, SOCK_STREAM, 0)
            return try { connectTo(fd, port) } finally { close(fd) }
        }

        fun connectTo(fd: Int, port: Int): Int = memScoped {
            val addr = alloc<sockaddr_in>()
            addr.sin_family = AF_INET.convert()
            addr.sin_addr.s_addr = LOOPBACK
            addr.sin_port = (((port and 0xFF) shl 8) or ((port shr 8) and 0xFF)).convert()
            connect(fd, addr.ptr.reinterpret<sockaddr>(), sizeOf<sockaddr_in>().convert())
        }
    }
}
