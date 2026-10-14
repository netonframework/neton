package neton.ai.adapter

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject
import neton.ai.AiError
import neton.ai.AiException
import neton.ai.AiLogSink
import neton.ai.AiStreamEvent
import neton.ai.internal.withRedactedValues
import neton.ai.protocol.codec.J
import neton.ai.protocol.codec.ProtocolCodecs
import neton.ai.protocol.convert.ProtocolConverter
import neton.ai.protocol.diag.Diagnostics
import neton.ai.protocol.ir.ChatRequest
import neton.ai.protocol.ir.WireFormat
import neton.ai.protocol.profile.ProviderProfile
import neton.ai.protocol.transport.SseFramer
import neton.ai.protocol.transport.Utf8StreamDecoder
import neton.ai.provider.AiStreamingTextModel
import neton.ai.provider.ProviderCallRequest
import neton.ai.provider.ProviderCallResponse
import neton.core.http.HttpHeaders
import neton.http.client.HttpClient
import neton.http.client.HttpClientBody
import neton.http.client.HttpClientError
import neton.http.client.HttpClientException
import neton.http.client.HttpClientMethod
import neton.http.client.HttpClientRequest
import neton.http.client.HttpClientStreamChunk

/** 一家厂商的 HTTP 端点：URL 与鉴权头。 */
internal interface ProtocolEndpoint {
    fun url(modelName: String, stream: Boolean): String
    fun headers(stream: Boolean): Map<String, String>
}

/**
 * 基于协议层编解码器的文本模型：四种协议共用一套 HTTP、错误映射与流式处理（spec §10），
 * 厂商差异只在 [format]、[endpoint] 与 [profile]。
 */
internal class ProtocolTextModel(
    override val providerId: String,
    override val modelName: String,
    private val format: WireFormat,
    private val httpClient: HttpClient,
    private val endpoint: ProtocolEndpoint,
    private val profile: ProviderProfile,
    private val logSink: AiLogSink? = null,
    private val debug: Boolean = false,
) : AiStreamingTextModel {
    private val codec = ProtocolCodecs.of(format)

    private companion object {
        val RESERVED_KEYS = setOf("model", "stream", "messages", "input", "contents", "system", "instructions", "systemInstruction")
    }

    private fun encode(request: ProviderCallRequest, stream: Boolean): String {
        val ir: ChatRequest = SdkIr.toChatRequest(modelName, request, stream, streamUsage = if (profile.streamUsage) true else null)
        val d = Diagnostics(target = format)
        val encoded = profile.adapt(format, ProtocolConverter.encodeRequest(ir, format, d))
        val body: JsonObject = request.options.providerOptions[providerId]?.let { extra ->
            // 厂商专属参数合并进请求体，保留字段不可覆盖（spec §4.3）
            JsonObject(encoded + extra.filterKeys { it !in RESERVED_KEYS })
        } ?: encoded
        if (debug && logSink != null && !d.isEmpty()) {
            logSink.invoke("ai.provider.$providerId model=$modelName diagnostics=${d.items.joinToString { it.code + "@" + it.path }}")
        }
        return body.toString()
    }

    private fun httpRequest(request: ProviderCallRequest, stream: Boolean): HttpClientRequest {
        val url = endpoint.url(modelName, stream)
        val headers = endpoint.headers(stream)
        if (debug && logSink != null) {
            logSink.invoke("ai.provider.$providerId model=$modelName POST $url${if (stream) " (stream)" else ""} headers=${headers.withRedactedValues()}")
        }
        return HttpClientRequest(
            method = HttpClientMethod.Post,
            url = url,
            headers = HttpHeaders.from(headers),
            body = HttpClientBody.Json(encode(request, stream)),
            metadata = request.metadata,
        )
    }

    override suspend fun generate(request: ProviderCallRequest): ProviderCallResponse {
        val resp = try {
            httpClient.request(httpRequest(request, stream = false))
        } catch (e: HttpClientException) {
            throw AiException(transportError(e.error))
        }
        if (resp.statusCode !in 200..299) {
            throw AiException(SdkIr.toAiError(codec.decodeError(resp.statusCode, resp.body), modelName))
        }
        val body = J.parseObject(resp.body) ?: throw AiException(AiError.Unknown("invalid JSON response from $providerId", null))
        return SdkIr.fromChatResponse(codec.decodeResponse(body))
    }

    override fun stream(request: ProviderCallRequest): Flow<AiStreamEvent> = flow {
        val adapter = SdkStreamAdapter(modelName)
        val decoder = codec.streamDecoder()
        val framer = SseFramer()
        val utf8 = Utf8StreamDecoder()
        var failed = false
        suspend fun handle(text: String) {
            for (ev in framer.feed(text)) for (ie in decoder.decode(ev)) for (out in adapter.accept(ie)) {
                if (out is AiStreamEvent.Failed) failed = true
                emit(out)
            }
        }
        try {
            httpClient.stream(httpRequest(request, stream = true)).collect { chunk ->
                if (failed) return@collect
                when (chunk) {
                    is HttpClientStreamChunk.Bytes -> handle(utf8.decode(chunk.bytes))
                    is HttpClientStreamChunk.Text -> handle(chunk.text)
                    is HttpClientStreamChunk.End -> {}
                }
            }
            if (failed) return@flow
            handle(utf8.flush())
            for (ev in framer.finish()) for (ie in decoder.decode(ev)) for (out in adapter.accept(ie)) emit(out)
            for (ie in decoder.finish()) for (out in adapter.accept(ie)) emit(out)
            for (out in adapter.finish(decoder.completed)) emit(out)
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpClientException) {
            val err = e.error
            throw AiException(if (err is HttpClientError.Http) {
                SdkIr.toAiError(codec.decodeError(err.statusCode, err.body ?: ""), modelName)
            } else transportError(err))
        } catch (e: AiException) {
            throw e
        } catch (e: Throwable) {
            throw AiException(AiError.Unknown(e.message ?: "stream error", e))
        }
    }

    private fun transportError(err: HttpClientError): AiError = when (err) {
        is HttpClientError.Network -> AiError.Network(err.message, err.cause)
        is HttpClientError.Timeout -> AiError.Timeout(err.message, err.cause)
        is HttpClientError.Http -> SdkIr.toAiError(codec.decodeError(err.statusCode, err.body ?: ""), modelName)
        is HttpClientError.Unknown -> AiError.Unknown(err.message, err.cause)
    }
}
