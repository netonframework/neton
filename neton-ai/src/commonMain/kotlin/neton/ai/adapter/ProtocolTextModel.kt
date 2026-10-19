package neton.ai.adapter

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject
import neton.ai.AiError
import neton.ai.AiException
import neton.ai.AiLogSink
import neton.ai.internal.ModelMapping
import neton.ai.internal.withRedactedValues
import neton.ai.model.AiLanguageModel
import neton.ai.model.AiModelCallOptions
import neton.ai.model.AiModelResult
import neton.ai.model.AiModelStream
import neton.ai.model.AiWarning
import neton.ai.protocol.codec.J
import neton.ai.protocol.codec.ProtocolCodecs
import neton.ai.protocol.convert.ProtocolConverter
import neton.ai.protocol.diag.Diagnostics
import neton.ai.protocol.ir.ChatRequest
import neton.ai.protocol.ir.StreamEvent
import neton.ai.protocol.ir.WireFormat
import neton.ai.protocol.profile.ProviderProfile
import neton.ai.protocol.transport.SseFramer
import neton.ai.protocol.transport.Utf8StreamDecoder
import neton.ai.protocol.usage.Usage
import neton.ai.protocol.usage.UsageCompleteness
import neton.core.http.HttpHeaders
import neton.http.client.HttpClient
import neton.http.client.HttpClientBody
import neton.http.client.HttpClientError
import neton.http.client.HttpClientException
import neton.http.client.HttpClientMethod
import neton.http.client.HttpClientRequest
import neton.http.client.HttpClientStreamChunk
import neton.http.client.HttpClientTimeouts

/** 一家厂商的 HTTP 端点：URL 与鉴权头。 */
internal interface ProtocolEndpoint {
    fun url(modelName: String, stream: Boolean): String
    fun headers(stream: Boolean): Map<String, String>
}

/**
 * 基于协议层编解码器的语言模型：五种协议共用一套 HTTP、错误映射与流式处理（spec §10），
 * 厂商差异只在 [format]、[endpoint] 与 [profile]。
 */
internal class ProtocolTextModel(
    override val provider: String,
    override val modelId: String,
    private val format: WireFormat,
    private val httpClient: HttpClient,
    private val endpoint: ProtocolEndpoint,
    private val profile: ProviderProfile,
    private val logSink: AiLogSink? = null,
    private val debug: Boolean = false,
) : AiLanguageModel {
    private val codec = ProtocolCodecs.of(format)

    private companion object {
        val RESERVED_KEYS = setOf("model", "stream", "messages", "input", "contents", "system", "instructions", "systemInstruction")
    }

    private class Encoded(val body: String, val warnings: List<AiWarning>)

    private fun encode(request: ChatRequest, stream: Boolean): Encoded {
        val ir = request.copy(model = modelId, stream = stream, streamUsage = if (stream && profile.streamUsage) true else null)
        val d = Diagnostics(target = format)
        val encoded = profile.adapt(format, ProtocolConverter.encodeRequest(ir, format, d))
        val body: JsonObject = request.options.providerOptions[provider]?.let { extra ->
            // 厂商专属参数合并进请求体，保留字段不可覆盖（spec §4.3）
            JsonObject(encoded + extra.filterKeys { it !in RESERVED_KEYS })
        } ?: encoded
        return Encoded(body.toString(), d.items.map(AiWarning::of))
    }

    private fun httpRequest(body: String, stream: Boolean, options: AiModelCallOptions): HttpClientRequest {
        val url = endpoint.url(modelId, stream)
        val headers = endpoint.headers(stream) + options.headers
        if (debug && logSink != null) {
            logSink.invoke("ai.provider.$provider model=$modelId POST $url${if (stream) " (stream)" else ""} headers=${headers.withRedactedValues()}")
        }
        return HttpClientRequest(
            method = HttpClientMethod.Post,
            url = url,
            headers = HttpHeaders.from(headers),
            body = HttpClientBody.Json(body),
            // 单次调用覆盖 provider 超时；provider 级默认由 ProviderTimeoutHttpClient 补齐。
            timeout = options.timeoutMillis?.let { HttpClientTimeouts(requestMillis = it, socketMillis = it) },
            metadata = options.metadata,
        )
    }

    override suspend fun generate(request: ChatRequest, options: AiModelCallOptions): AiModelResult {
        val enc = encode(request, stream = false)
        val resp = try {
            httpClient.request(httpRequest(enc.body, stream = false, options))
        } catch (e: HttpClientException) {
            throw AiException(transportError(e.error))
        }
        val headers = resp.headers.singleValues()
        if (resp.statusCode !in 200..299) {
            val err = ModelMapping.toAiError(codec.decodeError(resp.statusCode, resp.body), modelId)
            throw AiException(withRetryAfter(err, headers))
        }
        val body = J.parseObject(resp.body) ?: throw AiException(AiError.Unknown("invalid JSON response from $provider", null))
        return AiModelResult(codec.decodeResponse(body), enc.warnings, headers)
    }

    override suspend fun stream(request: ChatRequest, options: AiModelCallOptions): AiModelStream {
        val enc = encode(request, stream = true)
        val events = flow {
            val decoder = codec.streamDecoder()
            val framer = SseFramer()
            val utf8 = Utf8StreamDecoder()
            suspend fun handle(text: String) {
                for (ev in framer.feed(text)) for (ie in decoder.decode(ev)) emit(ie)
            }
            try {
                httpClient.stream(httpRequest(enc.body, stream = true, options)).collect { chunk ->
                    when (chunk) {
                        is HttpClientStreamChunk.Bytes -> handle(utf8.decode(chunk.bytes))
                        is HttpClientStreamChunk.Text -> handle(chunk.text)
                        is HttpClientStreamChunk.Start, is HttpClientStreamChunk.End -> {}
                    }
                }
                handle(utf8.flush())
                for (ev in framer.finish()) for (ie in decoder.decode(ev)) emit(ie)
                for (ie in decoder.finish()) emit(ie)
                // 上游没给终止标记：流被截断，用量只是部分的
                if (!decoder.completed) emit(StreamEvent.UsageUpdate(Usage(completeness = UsageCompleteness.PARTIAL)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpClientException) {
                throw AiException(transportError(e.error))
            } catch (e: AiException) {
                throw e
            } catch (e: Throwable) {
                throw AiException(AiError.Unknown(e.message ?: "stream error", e))
            }
        }
        return AiModelStream(enc.warnings, events)
    }

    private fun transportError(err: HttpClientError): AiError = when (err) {
        is HttpClientError.Network -> AiError.Network(err.message, err.cause)
        is HttpClientError.Timeout -> AiError.Timeout(err.message, err.cause)
        is HttpClientError.Http -> ModelMapping.toAiError(codec.decodeError(err.statusCode, err.body ?: ""), modelId)
        // Refused before any connection: nothing reached the provider.
        is HttpClientError.TargetRejected -> AiError.Network(err.message, null)
        is HttpClientError.Unknown -> AiError.Unknown(err.message, err.cause)
    }
}

/** 从响应头取 Retry-After（retry-after-ms 优先，其次 retry-after 秒数）。 */
internal fun withRetryAfter(err: AiError, headers: Map<String, String>): AiError {
    if (err !is AiError.RateLimited && err !is AiError.ServerError) return err
    val h = headers.mapKeys { it.key.lowercase() }
    val ms = h["retry-after-ms"]?.toDoubleOrNull()?.toLong() ?: h["retry-after"]?.toDoubleOrNull()?.let { (it * 1000).toLong() }
    return if (err is AiError.RateLimited && ms != null) err.copy(retryAfterMillis = ms) else err
}

internal fun HttpHeaders.singleValues(): Map<String, String> = names().associateWith { get(it) ?: "" }
