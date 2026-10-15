package neton.http.engine.default.client

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import neton.http.client.ConnectError
import neton.http.client.Connected
import neton.http.client.Connector
import neton.http.uri.Uri
import neton.io.bytes.Buffer
import neton.io.core.IoStream
import neton.io.core.StreamCapability
import neton.io.net.connect as tcpConnect
import neton.openssl.TlsContext
import neton.tls.peerIdentityOf
import neton.tls.systemTrustRootsPem
import neton.tls.tlsConnect
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * The connections of the built-in outbound client (hyper-util `HttpConnector` + hyper-rustls
 * `HttpsConnector` + reqwest's proxy tunnel, in one place):
 *
 * - `http` targets: TCP to the target, or to the proxy when one is configured (the request then goes
 *   out in absolute-form, see [NetonStreamHttpClient]);
 * - `https` targets: TCP to the target, or a `CONNECT host:port` tunnel through the proxy, then TLS
 *   verified against [tls] with SNI / IP identity of the target host. The server's ALPN choice decides
 *   HTTP/2 (`h2`) or HTTP/1.1 on the connection.
 *
 * With [maxConnectionsPerHost] each `scheme://authority` has at most that many open connections; one
 * more fails at once with a [ConnectError] (it does not queue), as [neton.http.client.HttpClientConfig]
 * documents. Without it the count is not limited.
 *
 * All of it runs on the client's reactor thread (no locking).
 */
internal class ClientConnector(
    private val connectTimeoutMillis: Long,
    private val proxy: ProxyEndpoint?,
    private val tls: TlsContext,
    private val maxConnectionsPerHost: Int?,
) : Connector {
    private val open = HashMap<String, Int>()

    /** Connections currently open per `scheme://authority` (tests). */
    fun openConnections(): Map<String, Int> = open.toMap()

    override suspend fun connect(uri: Uri): Connected {
        val scheme = uri.schemeStr
        val host = uri.host?.removeSurrounding("[", "]")?.ifEmpty { null } ?: throw ConnectError("invalid URL, missing host: $uri")
        val port = uri.portU16 ?: defaultPort(scheme) ?: throw ConnectError("invalid URL, unknown port: $uri")
        val key = "$scheme://${uri.authority}"
        val limit = maxConnectionsPerHost
        if (limit != null && (open[key] ?: 0) >= limit) {
            throw ConnectError("connection limit reached for $key (maxConnectionsPerHost=$limit)")
        }
        val connected = when (scheme) {
            "http" -> Connected(if (proxy != null) proxy.open(connectTimeoutMillis) else tcp(host, port))
            "https" -> {
                val transport = if (proxy != null) proxy.tunnel(host, port, connectTimeoutMillis) else tcp(host, port)
                // tlsConnect closes the transport when the handshake fails.
                val secured = tlsConnect(transport, tls, peerIdentityOf(host), handshakeTimeoutMillis = connectTimeoutMillis)
                Connected(secured, negotiatedH2 = secured.alpn == "h2")
            }
            else -> throw ConnectError("invalid URL, scheme is neither http nor https: $uri")
        }
        open[key] = (open[key] ?: 0) + 1
        return Connected(CountedStream(connected.stream) { release(key) }, connected.negotiatedH2)
    }

    private fun release(key: String) {
        val n = (open[key] ?: 0) - 1
        if (n <= 0) open.remove(key) else open[key] = n
    }

    private suspend fun tcp(host: String, port: Int): IoStream = connectTcp(host, port, connectTimeoutMillis)
}

/** The configured `proxyUrl`, parsed once at client construction. */
internal class ProxyEndpoint(
    val scheme: String,
    val host: String,
    val port: Int,
    /** `Proxy-Authorization` value from the URL's userinfo, or null. */
    val authorization: String?,
    private val tlsToProxy: () -> TlsContext,
) {
    /** A connection to the proxy itself (TLS when the proxy URL is `https`). */
    suspend fun open(connectTimeoutMillis: Long): IoStream {
        val tcp = connectTcp(host, port, connectTimeoutMillis)
        if (scheme != "https") return tcp
        return tlsConnect(tcp, tlsToProxy(), peerIdentityOf(host), handshakeTimeoutMillis = connectTimeoutMillis)
    }

    /**
     * A tunnel to [targetHost]:[targetPort] through the proxy (RFC 9110 §9.3.6). The client speaks
     * first on the tunnel (TLS ClientHello), so a conforming proxy sends nothing after its response
     * head; bytes past the head are a protocol error, not data to keep.
     */
    suspend fun tunnel(targetHost: String, targetPort: Int, connectTimeoutMillis: Long): IoStream {
        val stream = open(connectTimeoutMillis)
        try {
            val authority = authorityOf(targetHost, targetPort)
            val head = buildString {
                append("CONNECT ").append(authority).append(" HTTP/1.1\r\n")
                append("Host: ").append(authority).append("\r\n")
                if (authorization != null) append("Proxy-Authorization: ").append(authorization).append("\r\n")
                append("\r\n")
            }
            val out = Buffer(head.length)
            out.writeBytes(head.encodeToByteArray())
            while (!out.isEmpty) stream.write(out)
            stream.flush()
            val response = try {
                withTimeout(connectTimeoutMillis) { readHead(stream) }
            } catch (e: TimeoutCancellationException) {
                throw ConnectTimeoutException("proxy $host:$port did not answer CONNECT within ${connectTimeoutMillis}ms", e)
            }
            val statusLine = response.substringBefore("\r\n")
            val status = statusLine.split(' ').getOrNull(1)?.toIntOrNull()
            if (status == null || status !in 200..299) {
                throw ConnectError("proxy $host:$port refused CONNECT $authority: $statusLine")
            }
            return stream
        } catch (e: Throwable) {
            stream.close()
            throw e
        }
    }

    private suspend fun readHead(stream: IoStream): String {
        val buf = Buffer(1024)
        val head = ArrayList<Byte>(256)
        while (true) {
            if (stream.read(buf) < 0) throw ConnectError("proxy $host:$port closed the connection during CONNECT")
            val bytes = buf.readAll()
            for ((i, b) in bytes.withIndex()) {
                head += b
                val n = head.size
                if (n >= 4 && head[n - 4] == CR && head[n - 3] == LF && head[n - 2] == CR && head[n - 1] == LF) {
                    if (i != bytes.lastIndex) throw ConnectError("proxy $host:$port sent data after its CONNECT response")
                    return head.toByteArray().decodeToString()
                }
                if (n > MAX_PROXY_HEAD) throw ConnectError("proxy $host:$port sent an oversized CONNECT response")
            }
        }
    }

    companion object {
        private const val CR = '\r'.code.toByte()
        private const val LF = '\n'.code.toByte()
        private const val MAX_PROXY_HEAD = 16 * 1024

        /** Parses `http(s)://[user:pass@]host[:port]`; the config layer already checked the scheme. */
        @OptIn(ExperimentalEncodingApi::class)
        fun parse(url: String, tlsToProxy: () -> TlsContext): ProxyEndpoint {
            val uri = try {
                Uri.parse(url)
            } catch (e: Exception) {
                throw IllegalArgumentException("proxyUrl is not a valid URL: $url", e)
            }
            val scheme = requireNotNull(uri.schemeStr) { "proxyUrl must be absolute: $url" }
            require(scheme == "http" || scheme == "https") { "proxyUrl must be an http(s) URL: $url" }
            val authority = requireNotNull(uri.authority) { "proxyUrl has no host: $url" }.toString()
            val host = uri.host?.removeSurrounding("[", "]")?.ifEmpty { null } ?: throw IllegalArgumentException("proxyUrl has no host: $url")
            val port = uri.portU16 ?: defaultPort(scheme)!!
            val userInfo = authority.substringBeforeLast('@', missingDelimiterValue = "").ifEmpty { null }
            val authorization = userInfo?.let { "Basic " + Base64.encode(percentDecode(it).encodeToByteArray()) }
            return ProxyEndpoint(scheme, host, port, authorization, tlsToProxy)
        }

        private fun percentDecode(s: String): String {
            if ('%' !in s) return s
            val out = ArrayList<Byte>(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '%' && i + 2 <= s.lastIndex) {
                    val v = s.substring(i + 1, i + 3).toIntOrNull(16)
                    if (v != null) {
                        out += v.toByte(); i += 3; continue
                    }
                }
                for (b in c.toString().encodeToByteArray()) out += b
                i++
            }
            return out.toByteArray().decodeToString()
        }
    }
}

internal fun defaultPort(scheme: String?): Int? = when (scheme) {
    "http" -> 80
    "https" -> 443
    else -> null
}

internal fun authorityOf(host: String, port: Int): String = if (':' in host) "[$host]:$port" else "$host:$port"

private suspend fun connectTcp(host: String, port: Int, timeoutMillis: Long): IoStream = try {
    withTimeout(timeoutMillis) { tcpConnect(host, port) }
} catch (e: TimeoutCancellationException) {
    throw ConnectTimeoutException("connect to $host:$port timed out after ${timeoutMillis}ms", e)
}

/** Connecting (TCP, proxy CONNECT) took longer than `connectMillis`: a timeout, not a refusal. */
internal class ConnectTimeoutException(message: String, cause: Throwable?) : Exception(message, cause)

/** Calls [onClose] once, the first time the connection is closed (by the pool, the peer, or us). */
private class CountedStream(private val inner: IoStream, private val onClose: () -> Unit) : IoStream {
    private var closed = false
    override val capabilities: Set<StreamCapability> get() = inner.capabilities
    override suspend fun read(dst: Buffer): Int = inner.read(dst)
    override suspend fun write(src: Buffer): Int = inner.write(src)
    override suspend fun flush() = inner.flush()
    override suspend fun writev(buffers: Array<Buffer>, count: Int): Long = inner.writev(buffers, count)
    override suspend fun shutdownOutput() = inner.shutdownOutput()
    override fun setTimeouts(readTimeoutMillis: Long, writeTimeoutMillis: Long, idleTimeoutMillis: Long) =
        inner.setTimeouts(readTimeoutMillis, writeTimeoutMillis, idleTimeoutMillis)
    override fun setReadTimeout(millis: Long) = inner.setReadTimeout(millis)
    override fun close() {
        if (!closed) {
            closed = true
            onClose()
        }
        inner.close()
    }
}

/** The system's trusted roots with ALPN `h2` then `http/1.1`: shared by every client of the process. */
internal val defaultClientTls: TlsContext by lazy {
    TlsContext(server = false, trustRootsPem = systemTrustRootsPem(), alpnProtocols = listOf("h2", "http/1.1"))
}

/** For TLS to an `https://` proxy: the proxy speaks HTTP/1.1 (CONNECT), never h2. */
internal val proxyClientTls: TlsContext by lazy {
    TlsContext(server = false, trustRootsPem = systemTrustRootsPem(), alpnProtocols = listOf("http/1.1"))
}
