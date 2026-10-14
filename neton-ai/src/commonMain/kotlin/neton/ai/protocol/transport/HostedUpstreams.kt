package neton.ai.protocol.transport

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import neton.ai.protocol.transport.aws.SigV4

/**
 * 托管上游（spec §3.2）：协议不变，只换鉴权、路径与帧。这里只放协议事实，
 * 渠道配置（region、project、部署名从哪来）由宿主决定。
 */
object BedrockUpstream {
    const val SERVICE = "bedrock"
    const val ANTHROPIC_VERSION = "bedrock-2023-05-31"

    /** Anthropic Messages on Bedrock：`/model/{modelId}/invoke` 或 `/invoke-with-response-stream`。 */
    fun anthropicPath(modelId: String, stream: Boolean): String =
        "/model/${SigV4.uriEncode(modelId, encodeSlash = true)}/" + if (stream) "invoke-with-response-stream" else "invoke"

    /** body 去掉 model / stream（在路径上），补 anthropic_version。 */
    fun anthropicBody(body: JsonObject): JsonObject =
        JsonObject(mapOf("anthropic_version" to JsonPrimitive(ANTHROPIC_VERSION)) + body.filterKeys { it != "model" && it != "stream" })

    /** SigV4 签名后的完整请求头。 */
    fun signedHeaders(credentials: SigV4.Credentials, region: String, method: String, url: String, body: String, nowMs: Long): Map<String, String> =
        SigV4.sign(credentials, region, SERVICE, method, url,
            mapOf("Content-Type" to "application/json", "Accept" to "application/json"), body.encodeToByteArray(), nowMs).headers

    /** Converse（任意 Bedrock 模型）：`/model/{modelId}/converse` 或 `/converse-stream`；body 不含 modelId。 */
    fun conversePath(modelId: String, stream: Boolean): String =
        "/model/${SigV4.uriEncode(modelId, encodeSlash = true)}/" + if (stream) "converse-stream" else "converse"

    fun converseBody(body: JsonObject): JsonObject = JsonObject(body.filterKeys { it != "modelId" })

    /** 流式响应是 AWS event-stream 二进制帧。 */
    fun streamDecoder(): ByteStreamDecoder = neton.ai.protocol.transport.aws.EventStreamDecoder()
}

object VertexUpstream {
    const val ANTHROPIC_VERSION = "vertex-2023-10-16"

    fun anthropicPath(project: String, location: String, model: String, stream: Boolean): String =
        "/v1/projects/$project/locations/$location/publishers/anthropic/models/$model:" + if (stream) "streamRawPredict" else "rawPredict"

    /** body 去掉 model（在路径上），补 anthropic_version；stream 保留在 body 里。 */
    fun anthropicBody(body: JsonObject): JsonObject =
        JsonObject(mapOf("anthropic_version" to JsonPrimitive(ANTHROPIC_VERSION)) + body.filterKeys { it != "model" })

    fun geminiPath(project: String, location: String, model: String, stream: Boolean): String =
        "/v1/projects/$project/locations/$location/publishers/google/models/$model:" + if (stream) "streamGenerateContent?alt=sse" else "generateContent"

    /** `us-central1-aiplatform.googleapis.com` → `us-central1`；全局端点返回 `global`。 */
    fun locationFromHost(host: String): String? = when {
        host == "aiplatform.googleapis.com" -> "global"
        else -> Regex("^([a-z0-9-]+)-aiplatform\\.googleapis\\.com$").find(host)?.groupValues?.get(1)
    }
}

object GeminiUpstream {
    fun path(model: String, stream: Boolean): String =
        "/v1beta/models/$model:" + if (stream) "streamGenerateContent?alt=sse" else "generateContent"
}

object AzureUpstream {
    const val DEFAULT_API_VERSION = "2024-10-21"

    fun headers(apiKey: String): Map<String, String> = mapOf("api-key" to apiKey, "Content-Type" to "application/json")

    /**
     * 部署路径：`/openai/deployments/{deployment}{op}?api-version=…`。
     * Responses 只在 v1 面上：`/openai/v1/responses`，部署名走 body 的 model。
     */
    fun path(inboundPath: String, deployment: String, apiVersion: String? = null): String {
        if (inboundPath.endsWith("/responses")) return "/openai/v1/responses?api-version=preview"
        val op = when {
            inboundPath.endsWith("/embeddings") -> "/embeddings"
            inboundPath.endsWith("/images/generations") -> "/images/generations"
            inboundPath.startsWith("/v1/audio/") -> inboundPath.removePrefix("/v1")
            inboundPath.endsWith("/completions") && !inboundPath.endsWith("/chat/completions") -> "/completions"
            else -> "/chat/completions"
        }
        val v = apiVersion?.trim().takeUnless { it.isNullOrEmpty() } ?: DEFAULT_API_VERSION
        return "/openai/deployments/$deployment$op?api-version=$v"
    }
}
