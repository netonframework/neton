package neton.http.hyper4k

import hyper4k.Hyper4kClient
import hyper4k.Hyper4kClientClosedException
import hyper4k.Hyper4kClientError
import hyper4k.Hyper4kClientEvent
import hyper4k.Hyper4kClientOptions
import hyper4k.Hyper4kClientOverloadedException
import hyper4k.Hyper4kClientRequest
import hyper4k.Hyper4kErrorKind
import hyper4k.Hyper4kResponseStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
import neton.core.annotations.InternalNetonApi
import neton.core.http.HttpHeader
import neton.core.http.HttpHeaders
import neton.http.client.HttpClient
import neton.http.client.HttpClientBody
import neton.http.client.HttpClientCapability
import neton.http.client.HttpClientConfig
import neton.http.client.HttpClientError
import neton.http.client.HttpClientException
import neton.http.client.HttpClientRequest
import neton.http.client.HttpClientResponse
import neton.http.client.HttpClientStreamChunk

/**
 * Neton's outbound [HttpClient] on hyper4k (spec zh-hans/spec/http-engine.md §7.3).
 *
 * The translation is thin on purpose: `hyper4k.Hyper4kClient` already owns the
 * ABI obligations, so this class only maps Neton's request/response/error model
 * onto hyper4k's events and keeps Neton's two contracts: `request()` returns any
 * status, `stream()` throws [HttpClientError.Http] before the first chunk.
 */
@OptIn(InternalNetonApi::class)
internal class Hyper4kHttpClient(config: HttpClientConfig) : HttpClient {

    /**
     * What the conformance suite has verified at this layer. The engine also
     * does HTTP/2 over ALPN and custom CAs, but neither has an h2 or TLS origin
     * in the suite yet, and a capability without a test guarding it is exactly
     * what the capability model forbids. They join this set with their tests.
     */
    override val capabilities: Set<HttpClientCapability> = setOf(
        HttpClientCapability.STREAMING_BODY,
        HttpClientCapability.CANCELLATION,
        HttpClientCapability.PROXY,
    )

    private val defaults = config.toEffectiveTimeout()
    private val client = Hyper4kClient(
        Hyper4kClientOptions(
            connectTimeoutMillis = defaults.connectMillis,
            requestTimeoutMillis = defaults.requestMillis,
            readIdleTimeoutMillis = defaults.socketMillis,
            proxyUrl = config.proxyUrl,
        ),
    )
    private var closed = false

    override suspend fun request(request: HttpClientRequest): HttpClientResponse {
        val stream = open(request)
        var status = 0
        var headers = HttpHeaders.EMPTY
        var body = ByteArray(0)
        return withDeadlines(request, stream) {
            while (true) {
                when (val event = stream.next()) {
                    is Hyper4kClientEvent.Headers -> {
                        status = event.status
                        headers = event.headers.toNeton()
                    }
                    is Hyper4kClientEvent.Chunk -> body += event.bytes
                    is Hyper4kClientEvent.Done -> {
                        event.error?.let { throw HttpClientException(it.toNeton()) }
                        return@withDeadlines HttpClientResponse(status, headers, body.decodeToString())
                    }
                }
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        }
    }

    override fun stream(request: HttpClientRequest): Flow<HttpClientStreamChunk> = flow {
        val stream = open(request)
        var finished = false
        try {
            var headers = HttpHeaders.EMPTY
            var errorStatus = 0
            var errorBody = ByteArray(0)
            while (true) {
                when (val event = stream.next()) {
                    is Hyper4kClientEvent.Headers -> {
                        headers = event.headers.toNeton()
                        if (event.status !in 200..299) errorStatus = event.status
                    }
                    is Hyper4kClientEvent.Chunk -> {
                        if (errorStatus != 0) errorBody += event.bytes else emit(HttpClientStreamChunk.Bytes(event.bytes))
                    }
                    is Hyper4kClientEvent.Done -> {
                        finished = true
                        event.error?.let { throw HttpClientException(it.toNeton()) }
                        if (errorStatus != 0) {
                            // Same reason as the Ktor client: an error JSON must not
                            // flow through an SSE parser as zero events.
                            throw HttpClientException(
                                HttpClientError.Http(errorStatus, "HTTP $errorStatus", errorBody.decodeToString()),
                            )
                        }
                        emit(HttpClientStreamChunk.End(headers))
                        return@flow
                    }
                }
            }
        } finally {
            // Collector gone, deadline hit, or an error: the engine must stop
            // reading on our behalf. Harmless after Done.
            if (!finished) stream.cancel()
        }
    }

    override suspend fun close() {
        closed = true
        client.close()
    }

    private fun open(request: HttpClientRequest): Hyper4kResponseStream {
        if (closed) throw HttpClientException(HttpClientError.Unknown("HttpClient is closed", null))
        return try {
            client.send(request.toHyper4k())
        } catch (e: Hyper4kClientClosedException) {
            throw HttpClientException(HttpClientError.Unknown("HttpClient is closed", e))
        } catch (e: Hyper4kClientOverloadedException) {
            throw HttpClientException(HttpClientError.Network("hyper4k client overloaded: ${e.message}", e))
        } catch (e: IllegalArgumentException) {
            throw HttpClientException(HttpClientError.Unknown("invalid request: ${e.message}", e))
        }
    }

    /**
     * Per-request overrides the engine cannot take per request (`request_timeout_ms`
     * is client-wide) are enforced here with a coroutine deadline that also cancels
     * the engine-side request, so a timed-out call does not keep reading.
     */
    private suspend fun <T> withDeadlines(
        request: HttpClientRequest,
        stream: Hyper4kResponseStream,
        block: suspend () -> T,
    ): T {
        val limit = request.timeout?.requestMillis
        return try {
            if (limit != null) withTimeout(limit) { block() } else block()
        } catch (e: TimeoutCancellationException) {
            stream.cancel()
            throw HttpClientException(HttpClientError.Timeout("request exceeded ${limit}ms", e))
        } catch (e: CancellationException) {
            stream.cancel()
            throw e
        } catch (e: HttpClientException) {
            stream.cancel()
            throw e
        }
    }

    private fun HttpClientRequest.toHyper4k(): Hyper4kClientRequest {
        val bodyBytes: ByteArray
        val contentType: String?
        when (val b = body) {
            null -> { bodyBytes = ByteArray(0); contentType = null }
            is HttpClientBody.Json -> { bodyBytes = b.text.encodeToByteArray(); contentType = "application/json" }
            is HttpClientBody.Text -> { bodyBytes = b.text.encodeToByteArray(); contentType = b.contentType }
            is HttpClientBody.Bytes -> { bodyBytes = b.bytes; contentType = b.contentType }
        }
        val headerList = headers.asList().map { it.name to it.value }.toMutableList()
        if (contentType != null && headerList.none { it.first.equals("Content-Type", ignoreCase = true) }) {
            headerList += "Content-Type" to contentType
        }
        return Hyper4kClientRequest(
            method = method.name.uppercase(),
            url = url,
            headers = headerList,
            body = bodyBytes,
            readIdleTimeoutMillis = timeout?.socketMillis,
        )
    }

    private fun List<Pair<String, String>>.toNeton(): HttpHeaders =
        HttpHeaders.of(map { HttpHeader(it.first, it.second) })

    /** spec http-engine.md §5.4. */
    private fun Hyper4kClientError.toNeton(): HttpClientError = when (kind) {
        Hyper4kErrorKind.TIMEOUT -> HttpClientError.Timeout("request timeout: $message", null)
        Hyper4kErrorKind.IDLE_TIMEOUT -> HttpClientError.Timeout("read idle timeout: $message", null)
        Hyper4kErrorKind.CANCELLED -> HttpClientError.Network("cancelled: client closed", null)
        Hyper4kErrorKind.OUTCOME_UNKNOWN -> HttpClientError.Network("outcome unknown: $message", null)
        Hyper4kErrorKind.TRUNCATED -> HttpClientError.Network("truncated: $message", null)
        Hyper4kErrorKind.NONE -> HttpClientError.Unknown("engine reported an error of kind NONE", null)
        Hyper4kErrorKind.UNKNOWN -> HttpClientError.Unknown("unknown hyper4k error kind $rawKind: $message", null)
        else -> HttpClientError.Network("${kind.name.lowercase()}: $message", null)
    }
}

internal fun createHyper4kHttpClient(config: HttpClientConfig): HttpClient = Hyper4kHttpClient(config)
