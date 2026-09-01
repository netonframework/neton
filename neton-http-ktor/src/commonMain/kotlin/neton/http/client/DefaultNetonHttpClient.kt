package neton.http.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.headers
import neton.core.http.HttpHeader
import neton.core.http.HttpHeaders
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import neton.http.client.NetonHttpBody
import neton.http.client.NetonHttpClient
import neton.http.client.NetonHttpError
import neton.http.client.NetonHttpException
import neton.http.client.NetonHttpMethod
import neton.http.client.NetonHttpRequest
import neton.http.client.NetonHttpResponse
import neton.http.client.NetonHttpStreamChunk
import neton.http.client.NetonHttpTimeout

internal class DefaultNetonHttpClient(
    engineFactory: HttpClientEngineFactory<*> = defaultKtorEngine(),
    private val defaultTimeout: NetonHttpTimeout = NetonHttpTimeout(
        connectMillis = 5_000,
        requestMillis = 60_000,
        socketMillis = 60_000,
    ),
    private val proxyUrl: String? = null,
) : NetonHttpClient {

    private val client: HttpClient = HttpClient(engineFactory) {
        install(HttpTimeout) {
            connectTimeoutMillis = defaultTimeout.connectMillis
            requestTimeoutMillis = defaultTimeout.requestMillis
            socketTimeoutMillis = defaultTimeout.socketMillis
        }
        expectSuccess = false  // We map status manually
        if (proxyUrl != null) {
            engine {
                proxy = io.ktor.client.engine.ProxyBuilder.http(io.ktor.http.Url(proxyUrl))
            }
        }
    }

    override suspend fun request(request: NetonHttpRequest): NetonHttpResponse {
        val response: HttpResponse = try {
            client.request { applyRequest(request) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpRequestTimeoutException) {
            throw NetonHttpException(NetonHttpError.Timeout(e.message ?: "Request timeout", e))
        } catch (e: IOException) {
            throw NetonHttpException(NetonHttpError.Network(e.message ?: "Network error", e))
        } catch (e: Throwable) {
            throw NetonHttpException(NetonHttpError.Unknown(e.message ?: "Unknown HTTP error", e))
        }

        val bodyText = try {
            response.bodyAsText()
        } catch (e: Throwable) {
            throw NetonHttpException(NetonHttpError.Network("Failed to read response body: ${e.message}", e))
        }

        return NetonHttpResponse(
            statusCode = response.status.value,
            headers = response.headers.toNeton(),
            body = bodyText,
        )
    }

    override fun stream(request: NetonHttpRequest): Flow<NetonHttpStreamChunk> = channelFlow {
        client.prepareRequest { applyRequest(request) }.execute { response ->
            // Non-2xx: surface as typed Http error BEFORE streaming the body. Without this check,
            // an error JSON body (e.g. OpenAI 429) would flow through SSE parsing as zero events
            // and downstream mappers would emit a silent empty Completed.
            val status = response.status.value
            if (status !in 200..299) {
                val errorBody = try {
                    response.bodyAsText()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    null
                }
                throw NetonHttpException(
                    NetonHttpError.Http(
                        statusCode = status,
                        message = "HTTP $status",
                        body = errorBody,
                    )
                )
            }
            val channel: ByteReadChannel = response.bodyAsChannel()
            val buf = ByteArray(DEFAULT_STREAM_CHUNK_BYTES)
            try {
                while (true) {
                    val n = channel.readAvailable(buf)
                    if (n <= 0) break
                    send(NetonHttpStreamChunk.Bytes(buf.copyOf(n)))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                throw NetonHttpException(NetonHttpError.Network(e.message ?: "Stream read error", e))
            } catch (e: Throwable) {
                throw NetonHttpException(NetonHttpError.Unknown(e.message ?: "Unknown stream error", e))
            }
            send(
                NetonHttpStreamChunk.End(
                    finalHeaders = response.headers.toNeton()
                )
            )
        }
    }

    override suspend fun close() {
        client.close()
    }

    private fun HttpRequestBuilder.applyRequest(req: NetonHttpRequest) {
        method = req.method.toKtor()
        url(req.url)
        headers {
            req.headers.asList().forEach { append(it.name, it.value) }
        }
        req.body?.let { applyBody(it) }
        req.timeout?.let { t ->
            timeout {
                t.connectMillis?.let { connectTimeoutMillis = it }
                t.requestMillis?.let { requestTimeoutMillis = it }
                t.socketMillis?.let { socketTimeoutMillis = it }
            }
        }
    }

    private fun HttpRequestBuilder.applyBody(body: NetonHttpBody) {
        when (body) {
            is NetonHttpBody.Json -> {
                contentType(ContentType.Application.Json)
                setBody(body.text)
            }
            is NetonHttpBody.Text -> {
                contentType(ContentType.parse(body.contentType))
                setBody(body.text)
            }
            is NetonHttpBody.Bytes -> {
                contentType(ContentType.parse(body.contentType))
                setBody(body.bytes)
            }
        }
    }

    private fun NetonHttpMethod.toKtor(): HttpMethod = when (this) {
        NetonHttpMethod.Get -> HttpMethod.Get
        NetonHttpMethod.Post -> HttpMethod.Post
        NetonHttpMethod.Put -> HttpMethod.Put
        NetonHttpMethod.Delete -> HttpMethod.Delete
        NetonHttpMethod.Patch -> HttpMethod.Patch
        NetonHttpMethod.Head -> HttpMethod.Head
        NetonHttpMethod.Options -> HttpMethod.Options
    }

    companion object {
        private const val DEFAULT_STREAM_CHUNK_BYTES = 8 * 1024
    }
}

/**
 * Keeps every value. The previous mapping took `firstOrNull()` per name, which
 * dropped repeated `Set-Cookie` on the floor without a trace.
 */
private fun io.ktor.http.Headers.toNeton(): HttpHeaders =
    HttpHeaders.of(entries().flatMap { (name, values) -> values.map { HttpHeader(name, it) } })
