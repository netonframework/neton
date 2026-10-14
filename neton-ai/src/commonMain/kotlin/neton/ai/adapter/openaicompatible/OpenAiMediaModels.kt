package neton.ai.adapter.openaicompatible

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import neton.ai.AiError
import neton.ai.AiException
import neton.ai.AiUsage
import neton.ai.internal.Jsons
import neton.ai.internal.ModelMapping
import neton.ai.protocol.codec.OpenAiChatCodec
import neton.ai.protocol.endpoint.Endpoint
import neton.ai.protocol.endpoint.EndpointUsageExtractor
import neton.ai.protocol.usage.UsageCompleteness
import neton.ai.provider.*
import neton.core.http.HttpHeaders
import neton.http.client.HttpClient
import neton.http.client.HttpClientBody
import neton.http.client.HttpClientError
import neton.http.client.HttpClientException
import neton.http.client.HttpClientMethod
import neton.http.client.HttpClientRequest
import neton.http.client.HttpClientResponse

/** OpenAI 兼容端点的公共 HTTP 部分。 */
internal class OpenAiHttp(
    val provider: String,
    private val http: HttpClient,
    private val baseUrl: String,
    private val headers: () -> Map<String, String>,
) {
    suspend fun post(path: String, body: HttpClientBody, extraHeaders: Map<String, String>, modelId: String): HttpClientResponse {
        val resp = try {
            http.request(HttpClientRequest(
                method = HttpClientMethod.Post,
                url = baseUrl.trimEnd('/') + path,
                headers = HttpHeaders.from(headers() + extraHeaders),
                body = body,
            ))
        } catch (e: HttpClientException) {
            throw AiException(when (val err = e.error) {
                is HttpClientError.Network -> AiError.Network(err.message, err.cause)
                is HttpClientError.Timeout -> AiError.Timeout(err.message, err.cause)
                is HttpClientError.Http -> ModelMapping.toAiError(OpenAiChatCodec.decodeError(err.statusCode, err.body ?: ""), modelId)
                is HttpClientError.Unknown -> AiError.Unknown(err.message, err.cause)
            })
        }
        if (resp.statusCode !in 200..299) {
            throw AiException(ModelMapping.toAiError(OpenAiChatCodec.decodeError(resp.statusCode, resp.body), modelId))
        }
        return resp
    }

    /** 二进制响应（音频等）：走流式接口收集原始字节，避免按文本解码损坏数据。 */
    suspend fun postBytes(path: String, body: HttpClientBody, extraHeaders: Map<String, String>, modelId: String): ByteArray {
        val out = mutableListOf<ByteArray>()
        try {
            http.stream(HttpClientRequest(
                method = HttpClientMethod.Post,
                url = baseUrl.trimEnd('/') + path,
                headers = HttpHeaders.from(headers() + extraHeaders),
                body = body,
            )).collect { c ->
                when (c) {
                    is neton.http.client.HttpClientStreamChunk.Bytes -> out += c.bytes
                    is neton.http.client.HttpClientStreamChunk.Text -> out += c.text.encodeToByteArray()
                    is neton.http.client.HttpClientStreamChunk.End -> {}
                }
            }
        } catch (e: HttpClientException) {
            throw AiException(when (val err = e.error) {
                is HttpClientError.Network -> AiError.Network(err.message, err.cause)
                is HttpClientError.Timeout -> AiError.Timeout(err.message, err.cause)
                is HttpClientError.Http -> ModelMapping.toAiError(OpenAiChatCodec.decodeError(err.statusCode, err.body ?: ""), modelId)
                is HttpClientError.Unknown -> AiError.Unknown(err.message, err.cause)
            })
        }
        val total = out.sumOf { it.size }
        val bytes = ByteArray(total)
        var pos = 0
        for (b in out) { b.copyInto(bytes, pos); pos += b.size }
        return bytes
    }

    fun json(body: String): JsonObject = runCatching { Jsons.lenient.parseToJsonElement(body).jsonObject }
        .getOrElse { throw AiException(AiError.Unknown("invalid JSON response from $provider", null)) }
}

internal fun tokensUsage(input: Long?, output: Long? = null) = AiUsage(
    inputTokens = input, outputTokens = output,
    completeness = if (input != null) UsageCompleteness.COMPLETE else UsageCompleteness.UNKNOWN,
)

internal class OpenAiEmbeddingModel(override val modelId: String, private val api: OpenAiHttp) : AiEmbeddingModel {
    override val provider: String get() = api.provider

    override suspend fun embed(values: List<String>, options: EmbedOptions): EmbedResult {
        val body = buildJsonObject {
            put("model", modelId)
            put("input", JsonArray(values.map(::JsonPrimitive)))
            options.dimensions?.let { put("dimensions", it) }
            options.providerOptions[provider]?.forEach { (k, v) -> if (k !in setOf("model", "input")) put(k, v) }
        }
        val resp = api.post("/embeddings", HttpClientBody.Json(body.toString()), options.headers, modelId)
        val o = api.json(resp.body)
        val data = (o["data"] as? JsonArray).orEmpty().map { it.jsonObject }
            .sortedBy { (it["index"] as? JsonPrimitive)?.intOrNull ?: 0 }
        val embeddings = data.map { d -> (d["embedding"] as? JsonArray).orEmpty().map { (it as JsonPrimitive).floatOrNull ?: 0f }.toFloatArray() }
        if (embeddings.size != values.size) throw AiException(AiError.Unknown("expected ${values.size} embeddings, got ${embeddings.size}", null))
        val usage = EndpointUsageExtractor.extract(Endpoint.EMBEDDINGS, null, resp.body).tokens
        return EmbedResult(embeddings, ModelMapping.toUsage(usage))
    }
}

/** `/images/generations`：返回 b64_json（请求时显式要求）或 url（url 结果不下载，交给调用方）。 */
internal class OpenAiImageModel(override val modelId: String, private val api: OpenAiHttp) : AiImageModel {
    override val provider: String get() = api.provider
    override val maxImagesPerCall: Int get() = if (modelId.startsWith("dall-e-3")) 1 else 10

    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    override suspend fun generate(options: ImageOptions): ImageResult {
        val body = buildJsonObject {
            put("model", modelId)
            put("prompt", options.prompt)
            put("n", options.n)
            options.size?.let { put("size", it) }
            // gpt-image 系列总是返回 b64_json；dall-e 需要显式要求
            if (!modelId.startsWith("gpt-image")) put("response_format", "b64_json")
            options.providerOptions[provider]?.forEach { (k, v) -> if (k !in setOf("model", "prompt", "n")) put(k, v) }
        }
        val resp = api.post("/images/generations", HttpClientBody.Json(body.toString()), options.headers, modelId)
        val o = api.json(resp.body)
        val images = (o["data"] as? JsonArray).orEmpty().mapNotNull { d ->
            ((d as? JsonObject)?.get("b64_json") as? JsonPrimitive)?.contentOrNull?.let {
                GeneratedFile(kotlin.io.encoding.Base64.decode(it), sniffImage(it))
            }
        }
        val usage = EndpointUsageExtractor.extract(Endpoint.IMAGES_GENERATIONS, null, resp.body).tokens
        return ImageResult(images, ModelMapping.toUsage(usage))
    }

    private fun sniffImage(b64: String): String = when {
        b64.startsWith("iVBOR") -> "image/png"
        b64.startsWith("/9j/") -> "image/jpeg"
        b64.startsWith("UklGR") -> "image/webp"
        b64.startsWith("R0lGOD") -> "image/gif"
        else -> "image/png"
    }
}

internal class OpenAiSpeechModel(override val modelId: String, private val api: OpenAiHttp) : AiSpeechModel {
    override val provider: String get() = api.provider

    override suspend fun generate(options: SpeechOptions): SpeechResult {
        val format = options.outputFormat ?: "mp3"
        val body = buildJsonObject {
            put("model", modelId)
            put("input", options.text)
            put("voice", options.voice ?: "alloy")
            put("response_format", format)
            options.instructions?.let { put("instructions", it) }
            options.speed?.let { put("speed", it) }
            options.providerOptions[provider]?.forEach { (k, v) -> if (k !in setOf("model", "input")) put(k, v) }
        }
        val bytes = api.postBytes("/audio/speech", HttpClientBody.Json(body.toString()), options.headers, modelId)
        if (bytes.isEmpty()) throw AiException(AiError.Unknown("no audio generated", null))
        return SpeechResult(GeneratedFile(bytes, mediaTypeFor(format)))
    }

    private fun mediaTypeFor(f: String) = when (f) {
        "mp3" -> "audio/mpeg"; "wav" -> "audio/wav"; "opus" -> "audio/opus"; "aac" -> "audio/aac"; "flac" -> "audio/flac"; "pcm" -> "audio/pcm"
        else -> "application/octet-stream"
    }
}

/** `/audio/transcriptions`（multipart 上传），要求 verbose_json 以拿到分段与时长。 */
internal class OpenAiTranscriptionModel(override val modelId: String, private val api: OpenAiHttp) : AiTranscriptionModel {
    override val provider: String get() = api.provider

    override suspend fun transcribe(options: TranscriptionOptions): TranscriptionResult {
        val boundary = "neton-${kotlin.random.Random.nextLong().toULong().toString(16)}"
        val verbose = modelId.startsWith("whisper")
        val fields = buildList {
            add("model" to modelId)
            add("response_format" to if (verbose) "verbose_json" else "json")
            options.language?.let { add("language" to it) }
            options.prompt?.let { add("prompt" to it) }
            options.providerOptions[provider]?.forEach { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { add(k to it) } }
        }
        val body = multipart(boundary, fields, "file", "audio.${extension(options.mediaType)}", options.mediaType, options.audio)
        val resp = api.post("/audio/transcriptions", HttpClientBody.Bytes(body, "multipart/form-data; boundary=$boundary"), options.headers, modelId)
        val o = api.json(resp.body)
        val segments = (o["segments"] as? JsonArray).orEmpty().mapNotNull { s ->
            val so = s as? JsonObject ?: return@mapNotNull null
            TranscriptSegment(
                (so["text"] as? JsonPrimitive)?.contentOrNull?.trim() ?: "",
                (so["start"] as? JsonPrimitive)?.doubleOrNull ?: 0.0,
                (so["end"] as? JsonPrimitive)?.doubleOrNull ?: 0.0,
            )
        }
        val extracted = EndpointUsageExtractor.extract(Endpoint.AUDIO_TRANSCRIPTIONS, null, resp.body)
        val text = (o["text"] as? JsonPrimitive)?.contentOrNull ?: ""
        if (text.isEmpty()) throw AiException(AiError.Unknown("no transcript generated", null))
        return TranscriptionResult(
            text = text,
            segments = segments,
            language = (o["language"] as? JsonPrimitive)?.contentOrNull,
            durationInSeconds = (o["duration"] as? JsonPrimitive)?.doubleOrNull ?: extracted.units[neton.ai.protocol.endpoint.EndpointUsage.UNIT_AUDIO_SECONDS],
            usage = ModelMapping.toUsage(extracted.tokens),
        )
    }

    private fun extension(mediaType: String) = when (mediaType.substringAfter('/')) {
        "mpeg" -> "mp3"; "x-wav", "wave" -> "wav"; "webm" -> "webm"; "mp4" -> "mp4"; "ogg" -> "ogg"; "flac" -> "flac"
        else -> mediaType.substringAfter('/')
    }

    private fun multipart(boundary: String, fields: List<Pair<String, String>>, fileField: String, filename: String, mediaType: String, data: ByteArray): ByteArray {
        val sb = StringBuilder()
        for ((k, v) in fields) sb.append("--$boundary\r\nContent-Disposition: form-data; name=\"$k\"\r\n\r\n$v\r\n")
        sb.append("--$boundary\r\nContent-Disposition: form-data; name=\"$fileField\"; filename=\"$filename\"\r\nContent-Type: $mediaType\r\n\r\n")
        return sb.toString().encodeToByteArray() + data + "\r\n--$boundary--\r\n".encodeToByteArray()
    }
}

/** `/rerank`（Cohere / Jina / vLLM 等通行的形状）。 */
internal class OpenAiRerankingModel(override val modelId: String, private val api: OpenAiHttp) : AiRerankingModel {
    override val provider: String get() = api.provider

    override suspend fun rerank(query: String, documents: List<String>, topN: Int?, headers: Map<String, String>): RerankResult {
        if (documents.isEmpty()) return RerankResult(emptyList())
        val body = buildJsonObject {
            put("model", modelId)
            put("query", query)
            put("documents", buildJsonArray { documents.forEach { add(JsonPrimitive(it)) } })
            topN?.let { put("top_n", it) }
        }
        val resp = api.post("/rerank", HttpClientBody.Json(body.toString()), headers, modelId)
        val o = api.json(resp.body)
        val ranking = (o["results"] as? JsonArray ?: o["data"] as? JsonArray).orEmpty().mapNotNull { r ->
            val ro = r as? JsonObject ?: return@mapNotNull null
            val index = (ro["index"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
            RankedDocument(index, (ro["relevance_score"] as? JsonPrimitive)?.doubleOrNull ?: (ro["score"] as? JsonPrimitive)?.doubleOrNull ?: 0.0)
        }.sortedByDescending { it.score }
        return RerankResult(ranking)
    }
}

private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()
