@file:OptIn(ExperimentalForeignApi::class)

package neton.http.netonstream

import kotlinx.cinterop.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import neton.core.component.CorsConfig
import neton.core.component.NetonContext
import neton.core.http.HandlerArgs
import neton.core.http.HttpContext
import neton.core.http.HttpMethod
import neton.core.http.adapter.HttpServerConfig
import neton.core.interfaces.ConfiguredRouteGroups
import neton.core.interfaces.RequestEngine
import neton.core.interfaces.RouteDefinition
import neton.core.interfaces.RouteHandler
import neton.io.bytes.Bytes
import neton.io.core.IoStream
import neton.io.net.connect
import neton.io.net.runReactor
import neton.openssl.PeerIdentity
import neton.openssl.TlsContext
import neton.openssl.c.*
import neton.tls.tlsConnect
import platform.posix.AF_INET
import platform.posix.SIGPIPE
import platform.posix.SIG_IGN
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_RCVTIMEO
import platform.posix.getsockname
import platform.posix.recv
import platform.posix.send
import platform.posix.setsockopt
import platform.posix.signal
import platform.posix.sockaddr
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.socklen_tVar
import platform.posix.timeval
import neton.http.Body as StreamBody
import neton.http.EmptyBody as StreamEmptyBody
import neton.http.Frame as StreamFrame
import neton.http.FullBody as StreamFullBody
import neton.http.Request as StreamRequest
import neton.http.h1.http1Handshake
import neton.http.h2.http2Handshake

// ---------------------------------------------------------------------------------------------
// Application fixture
// ---------------------------------------------------------------------------------------------

internal class FixtureRequestEngine(private val routes: List<RouteDefinition>) : RequestEngine {
    override fun registerRoute(route: RouteDefinition) = Unit
    override fun getRoutes(): List<RouteDefinition> = routes
}

internal fun fixtureContext(routes: List<RouteDefinition>, cors: CorsConfig? = null) = NetonContext(emptyArray()).apply {
    bind(RequestEngine::class, FixtureRequestEngine(routes))
    bind(ConfiguredRouteGroups(emptySet()))
    cors?.let { bind(CorsConfig::class, it) }
}

internal fun route(method: HttpMethod, pattern: String, handler: suspend (HttpContext) -> Any?) = RouteDefinition(
    pattern = pattern,
    method = method,
    allowAnonymous = true,
    handler = object : RouteHandler {
        override suspend fun invoke(context: HttpContext, args: HandlerArgs): Any? = handler(context)
    },
)

internal fun get(pattern: String, handler: suspend (HttpContext) -> Any?) = route(HttpMethod.GET, pattern, handler)
internal fun post(pattern: String, handler: suspend (HttpContext) -> Any?) = route(HttpMethod.POST, pattern, handler)

// ---------------------------------------------------------------------------------------------
// Server under test
// ---------------------------------------------------------------------------------------------

internal class TestServer(val adapter: NetonStreamHttpAdapter, val port: Int, private val job: Job) {
    suspend fun stop() {
        adapter.stop()
        job.join()
    }
}

/** Starts the real adapter (reactors on their own threads) and waits until it is serving. */
internal suspend fun startServer(
    routes: List<RouteDefinition>,
    options: NetonStreamOptions = NetonStreamOptions(host = "127.0.0.1", reactors = 2),
    cors: CorsConfig? = null,
    config: (port: Int) -> HttpServerConfig = { HttpServerConfig(port = it) },
): TestServer {
    ignoreSigpipe()
    val port = freePort()
    val adapter = NetonStreamHttpAdapter(config(port), options)
    val started = CompletableDeferred<Unit>()
    val job = CoroutineScope(Dispatchers.Default).launch {
        adapter.start(fixtureContext(routes, cors)) { started.complete(Unit) }
    }
    job.invokeOnCompletion { e -> if (e != null) started.completeExceptionally(e) else started.complete(Unit) }
    withTimeout(10_000) { started.await() }
    return TestServer(adapter, port, job)
}

internal fun ignoreSigpipe() {
    signal(SIGPIPE, SIG_IGN)
}

private const val LOOPBACK: UInt = 0x0100007Fu

internal fun freePort(): Int = memScoped {
    val fd = socket(AF_INET, SOCK_STREAM, 0)
    check(fd >= 0) { "socket() failed" }
    try {
        val addr = alloc<sockaddr_in>()
        addr.sin_family = AF_INET.convert()
        addr.sin_addr.s_addr = LOOPBACK
        addr.sin_port = 0u
        check(platform.posix.bind(fd, addr.ptr.reinterpret<sockaddr>(), sizeOf<sockaddr_in>().convert()) == 0) { "bind() failed" }
        val length = alloc<socklen_tVar>()
        length.value = sizeOf<sockaddr_in>().convert()
        check(getsockname(fd, addr.ptr.reinterpret<sockaddr>(), length.ptr) == 0) { "getsockname() failed" }
        val networkOrder = addr.sin_port.toInt()
        ((networkOrder and 0xFF) shl 8) or ((networkOrder shr 8) and 0xFF)
    } finally {
        platform.posix.close(fd)
    }
}

// ---------------------------------------------------------------------------------------------
// Raw HTTP/1.1 client over a blocking socket: exact bytes on the wire, pipelining, disconnects
// ---------------------------------------------------------------------------------------------

internal class RawResponse(
    val status: Int,
    /** Lowercase names, every value kept in order. */
    val headers: Map<String, List<String>>,
    val body: ByteArray,
    val chunks: List<ByteArray>,
) {
    fun header(name: String): String? = headers[name.lowercase()]?.firstOrNull()
    val text: String get() = body.decodeToString()
}

internal class RawClient(val port: Int, receiveTimeoutSeconds: Int = 10) {
    private val fd = socket(AF_INET, SOCK_STREAM, 0)
    private var buffer = ByteArray(0)
    private var closed = false

    init {
        check(fd >= 0) { "socket() failed" }
        memScoped {
            val addr = alloc<sockaddr_in>()
            addr.sin_family = AF_INET.convert()
            addr.sin_addr.s_addr = LOOPBACK
            addr.sin_port = (((port and 0xFF) shl 8) or ((port shr 8) and 0xFF)).convert()
            check(platform.posix.connect(fd, addr.ptr.reinterpret<sockaddr>(), sizeOf<sockaddr_in>().convert()) == 0) {
                "connect() to 127.0.0.1:$port failed"
            }
            val timeout = alloc<timeval>()
            timeout.tv_sec = receiveTimeoutSeconds.convert()
            timeout.tv_usec = 0.convert()
            setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, timeout.ptr, sizeOf<timeval>().convert())
        }
    }

    fun send(text: String) = send(text.encodeToByteArray())

    fun send(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        bytes.usePinned { pinned ->
            var sent = 0
            while (sent < bytes.size) {
                val n = send(fd, pinned.addressOf(sent), (bytes.size - sent).convert(), 0).toInt()
                if (n < 0 && platform.posix.errno == platform.posix.EINTR) continue
                check(n > 0) { "send() failed" }
                sent += n
            }
        }
    }

    /** One read into the buffer; false once the peer closed or the read timed out. */
    private fun fill(): Boolean {
        val chunk = ByteArray(16 * 1024)
        val n = receive(chunk)
        if (n <= 0) return false
        buffer += chunk.copyOfRange(0, n)
        return true
    }

    /** Reads just the response head (status and headers), leaving the body in the buffer. */
    fun readHead(): Pair<Int, Map<String, List<String>>> {
        while (true) {
            val end = indexOf(buffer, "\r\n\r\n".encodeToByteArray(), 0)
            if (end >= 0) {
                val lines = buffer.decodeToString(0, end).split("\r\n")
                buffer = buffer.copyOfRange(end + 4, buffer.size)
                val status = lines.first().split(" ").getOrNull(1)?.toIntOrNull() ?: 0
                val headers = LinkedHashMap<String, MutableList<String>>()
                for (line in lines.drop(1)) {
                    val i = line.indexOf(':')
                    if (i <= 0) continue
                    headers.getOrPut(line.substring(0, i).trim().lowercase()) { mutableListOf() }.add(line.substring(i + 1).trim())
                }
                return status to headers
            }
            check(fill()) { "connection ended before a response head" }
        }
    }

    /** Reads one complete response (Content-Length, chunked, or until close). */
    fun readResponse(headRequest: Boolean = false): RawResponse {
        val (status, headers) = readHead()
        if (headRequest || status == 204 || status == 304 || status in 100..199) {
            return RawResponse(status, headers, ByteArray(0), emptyList())
        }
        val length = headers["content-length"]?.firstOrNull()?.toInt()
        val chunked = headers["transfer-encoding"]?.any { it.contains("chunked", ignoreCase = true) } == true
        return when {
            chunked -> {
                val chunks = mutableListOf<ByteArray>()
                while (true) {
                    val chunk = nextChunk() ?: break
                    chunks += chunk
                }
                RawResponse(status, headers, chunks.fold(ByteArray(0)) { a, b -> a + b }, chunks)
            }
            length != null -> {
                while (buffer.size < length) check(fill()) { "connection ended inside a body" }
                val body = buffer.copyOfRange(0, length)
                buffer = buffer.copyOfRange(length, buffer.size)
                RawResponse(status, headers, body, listOf(body))
            }
            else -> {
                while (fill()) { }
                val body = buffer
                buffer = ByteArray(0)
                RawResponse(status, headers, body, listOf(body))
            }
        }
    }

    /** The next chunk of a chunked body; null at its end. */
    fun nextChunk(): ByteArray? {
        while (true) {
            val lineEnd = indexOf(buffer, "\r\n".encodeToByteArray(), 0)
            if (lineEnd >= 0) {
                val size = buffer.decodeToString(0, lineEnd).substringBefore(';').trim().toInt(16)
                if (size == 0) {
                    // Last chunk and (empty) trailer section.
                    while (indexOf(buffer, "\r\n\r\n".encodeToByteArray(), lineEnd) < 0) check(fill()) { "no chunked terminator" }
                    val end = indexOf(buffer, "\r\n\r\n".encodeToByteArray(), lineEnd)
                    buffer = buffer.copyOfRange(end + 4, buffer.size)
                    return null
                }
                val dataStart = lineEnd + 2
                while (buffer.size < dataStart + size + 2) check(fill()) { "connection ended inside a chunk" }
                val data = buffer.copyOfRange(dataStart, dataStart + size)
                buffer = buffer.copyOfRange(dataStart + size + 2, buffer.size)
                return data
            }
            check(fill()) { "connection ended before a chunk" }
        }
    }

    /** True if the server closed the connection (EOF) within the receive timeout, with nothing more to read. */
    fun serverClosed(): Boolean {
        if (buffer.isNotEmpty()) return false
        val chunk = ByteArray(1024)
        val n = receive(chunk)
        if (n > 0) buffer += chunk.copyOfRange(0, n)
        return n == 0
    }

    fun close() {
        if (!closed) {
            closed = true
            platform.posix.close(fd)
        }
    }

    // A runtime signal can interrupt blocking recv; EINTR is not EOF or a peer failure.
    private fun receive(chunk: ByteArray): Int = chunk.usePinned { pinned ->
        var n: Int
        do {
            n = recv(fd, pinned.addressOf(0), chunk.size.convert(), 0).toInt()
        } while (n < 0 && platform.posix.errno == platform.posix.EINTR)
        n
    }
}

private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int): Int {
    outer@ for (i in from..haystack.size - needle.size) {
        for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
        return i
    }
    return -1
}

internal fun getRequest(path: String, extra: String = "", host: String = "localhost"): String =
    "GET $path HTTP/1.1\r\nHost: $host\r\n$extra\r\n"

// ---------------------------------------------------------------------------------------------
// Library clients: HTTP/1.1 and HTTP/2 (prior knowledge or over TLS with ALPN)
// ---------------------------------------------------------------------------------------------

internal class ClientResult(
    val alpn: String?,
    val status: Int,
    val version: String,
    /** Lowercase names. */
    val headers: Map<String, List<String>>,
    val body: ByteArray,
) {
    fun header(name: String): String? = headers[name.lowercase()]?.firstOrNull()
    val text: String get() = body.decodeToString()
}

/**
 * One request with com.netonstream:http's own client: HTTP/2 when [h2] (prior knowledge on
 * cleartext, or over TLS when [tls] is given), HTTP/1.1 otherwise. Blocks the calling thread
 * (the client runs on a reactor of its own).
 */
internal fun clientRequest(
    port: Int,
    path: String,
    h2: Boolean,
    tls: TlsContext? = null,
    method: String = "GET",
    headers: Map<String, String> = emptyMap(),
    body: ByteArray? = null,
): ClientResult {
    var result: ClientResult? = null
    runReactor {
        val tcp = connect("127.0.0.1", port)
        var alpn: String? = null
        val stream: IoStream = if (tls != null) {
            tlsConnect(tcp, tls, PeerIdentity.Ip("127.0.0.1")).also { alpn = it.alpn }
        } else {
            tcp
        }
        coroutineScope {
            val builder = StreamRequest.builder().method(method)
                .uri(if (h2) "http://127.0.0.1:$port$path" else path)
            if (!h2) builder.header("host", "127.0.0.1:$port")
            for ((k, v) in headers) builder.header(k, v)
            val requestBody: StreamBody = if (body == null) StreamEmptyBody else StreamFullBody(Bytes.wrap(body))
            val request = builder.body(requestBody)
            val response = if (h2) {
                val (sender, connection) = http2Handshake(stream)
                val driver = launch { runCatching { connection.run() } }
                sender.ready()
                val r = sender.sendRequest(request)
                val out = r to readAll(r.body)
                driver.cancel()
                out
            } else {
                val (sender, connection) = http1Handshake(stream)
                val driver = launch { runCatching { connection.run() } }
                sender.ready()
                val r = sender.sendRequest(request)
                val out = r to readAll(r.body)
                driver.cancel()
                out
            }
            val (r, bytes) = response
            val map = LinkedHashMap<String, MutableList<String>>()
            r.headers.forEach { name, value -> map.getOrPut(name.asStr()) { mutableListOf() }.add(value.asBytes().decodeToString()) }
            result = ClientResult(alpn, r.status.asU16(), r.version.toString(), map, bytes)
        }
        stream.close()
    }
    return result!!
}

private suspend fun readAll(body: StreamBody): ByteArray {
    var out = ByteArray(0)
    while (true) {
        val frame = body.nextFrame() ?: break
        if (frame is StreamFrame.Data) out += frame.bytes.toByteArray()
    }
    return out
}

internal fun clientTls(identity: TestIdentity, alpn: List<String>): TlsContext =
    TlsContext(false, identity.certificate, alpnProtocols = alpn)

// ---------------------------------------------------------------------------------------------
// Test certificates, generated with OpenSSL at test time (same construction as the tls repository's
// test fixture): EC P-256, CN=localhost, SAN DNS:localhost + IP:127.0.0.1, valid one hour.
// ---------------------------------------------------------------------------------------------

internal class TestIdentity(val certificate: ByteArray, val key: ByteArray)

internal fun testIdentity(): TestIdentity = memScoped {
    val keyContext = checkNotNull(EVP_PKEY_CTX_new_from_name(null, "EC", null))
    val out = alloc<CPointerVar<EVP_PKEY>>()
    out.value = null
    try {
        check(EVP_PKEY_keygen_init(keyContext) == 1)
        check(EVP_PKEY_CTX_set_group_name(keyContext, "prime256v1") == 1)
        check(EVP_PKEY_generate(keyContext, out.ptr) == 1)
    } finally {
        EVP_PKEY_CTX_free(keyContext)
    }
    val key = checkNotNull(out.value)
    val cert = checkNotNull(X509_new())
    try {
        check(X509_set_version(cert, 2) == 1)
        check(ASN1_INTEGER_set(X509_get_serialNumber(cert), 1) == 1)
        checkNotNull(X509_gmtime_adj(X509_getm_notBefore(cert), -3600))
        checkNotNull(X509_gmtime_adj(X509_getm_notAfter(cert), 3600))
        check(X509_set_pubkey(cert, key) == 1)
        val subject = checkNotNull(X509_get_subject_name(cert))
        check(X509_NAME_add_entry_by_txt(subject, "CN", MBSTRING_ASC, "localhost".cstr.ptr.reinterpret(), -1, -1, 0) == 1)
        check(X509_set_issuer_name(cert, subject) == 1)
        val san = checkNotNull(X509V3_EXT_conf_nid(null, null, NID_subject_alt_name, "DNS:localhost,IP:127.0.0.1"))
        try { check(X509_add_ext(cert, san, -1) == 1) } finally { X509_EXTENSION_free(san) }
        check(X509_sign(cert, key, EVP_sha256()) > 0)
        fun encode(writer: (CPointer<BIO>) -> Int): ByteArray {
            val bio = checkNotNull(BIO_new(BIO_s_mem()))
            try {
                check(writer(bio) == 1)
                val bytes = ByteArray(BIO_ctrl_pending(bio).toInt())
                bytes.usePinned { check(BIO_read(bio, it.addressOf(0), bytes.size) == bytes.size) }
                return bytes
            } finally {
                BIO_free(bio)
            }
        }
        TestIdentity(
            encode { PEM_write_bio_X509(it, cert) },
            encode { PEM_write_bio_PrivateKey(it, key, null, null, 0, null, null) },
        )
    } finally {
        X509_free(cert)
        EVP_PKEY_free(key)
    }
}

/** Writes [bytes] to a fresh file under /tmp and returns its path. */
internal fun writeTempFile(name: String, bytes: ByteArray): String {
    val path = "/tmp/neton-netonstream-test-${platform.posix.getpid()}-$name"
    val file = checkNotNull(platform.posix.fopen(path, "wb")) { "cannot create $path" }
    try {
        if (bytes.isNotEmpty()) {
            val written = bytes.usePinned { platform.posix.fwrite(it.addressOf(0), 1u.convert(), bytes.size.convert(), file) }
            check(written.toInt() == bytes.size) { "short write to $path" }
        }
    } finally {
        platform.posix.fclose(file)
    }
    return path
}
