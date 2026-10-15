package neton.ai.mcp

import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.takeWhile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import neton.ai.protocol.transport.SseEvent
import neton.ai.protocol.transport.SseFramer
import neton.ai.protocol.transport.Utf8StreamDecoder
import neton.core.http.HttpHeaders
import neton.http.client.HttpClient
import neton.http.client.HttpClientBody
import neton.http.client.HttpClientError
import neton.http.client.HttpClientException
import neton.http.client.HttpClientMethod
import neton.http.client.HttpClientRequest
import neton.http.client.HttpClientStreamChunk

internal class McpReply(val message: JsonObject?, val headers: HttpHeaders)

/**
 * Streamable HTTP 传输：发出一条 JSON-RPC 消息，**增量**读取响应（JSON 或 SSE）。
 *
 * - 等到对应 id 的响应就停止读取并断开，不等连接结束；
 * - 响应之前到达的通知与服务端请求交给 `onInbound`；
 * - 响应体累计超过 [maxResponseBytes] 即失败；
 * - SSE 在响应到达前断开时，如果允许续传且见过事件 id，用 `Last-Event-ID` 发 GET 续读（legacy 协议）。
 *
 * 只管一次请求的收发，不知道会话与协议世代——那是 [McpClient] 的事。
 */
internal class McpHttpTransport(
    private val http: HttpClient,
    private val url: String,
    private val maxResponseBytes: Long,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun send(
        message: JsonObject,
        headers: Map<String, String>,
        expectId: Int?,
        resumable: Boolean,
        onInbound: suspend (JsonObject) -> Unit,
    ): McpReply {
        val reader = Reader(expectId, onInbound)
        var request = HttpClientRequest(
            method = HttpClientMethod.Post, url = url,
            headers = HttpHeaders.from(headers + ("Content-Type" to "application/json")),
            body = HttpClientBody.Json(message.toString()),
        )
        var resumes = 0
        while (true) {
            read(request, reader)
            if (expectId == null || reader.response != null) return McpReply(reader.response, reader.headers ?: HttpHeaders.EMPTY)
            val lastEventId = reader.lastEventId
            if (!resumable || !reader.sse || lastEventId == null || resumes++ >= MAX_RESUMES) {
                throw McpException(McpErrorKind.Transport, "MCP response stream ended before the response arrived", outcomeUnknown = true)
            }
            request = HttpClientRequest(
                method = HttpClientMethod.Get, url = url,
                headers = HttpHeaders.from(headers + mapOf("Accept" to "text/event-stream", "Last-Event-ID" to lastEventId)),
            )
        }
    }

    private class Reader(val expectId: Int?, val onInbound: suspend (JsonObject) -> Unit) {
        var response: JsonObject? = null
        var headers: HttpHeaders? = null
        var sse = false
        var lastEventId: String? = null
        var bytes = 0L
    }

    private suspend fun read(request: HttpClientRequest, r: Reader) {
        val framer = SseFramer()
        val utf8 = Utf8StreamDecoder()
        val body = StringBuilder()

        suspend fun events(list: List<SseEvent>) {
            for (ev in list) {
                ev.id?.let { r.lastEventId = it }
                if (ev.data.isNotBlank()) dispatch(parse(ev.data), r)
                if (r.response != null) return
            }
        }
        suspend fun text(t: String) {
            if (r.sse) events(framer.feed(t)) else body.append(t)
        }
        fun count(n: Int) {
            r.bytes += n
            if (r.bytes > maxResponseBytes) throw McpException(McpErrorKind.Protocol, "MCP response exceeds $maxResponseBytes bytes")
        }

        try {
            http.stream(request).takeWhile { chunk ->
                when (chunk) {
                    is HttpClientStreamChunk.Start -> {
                        r.headers = chunk.headers
                        r.sse = chunk.headers.get("Content-Type")?.lowercase()?.startsWith("text/event-stream") == true
                        // 通知没有响应：拿到响应头（通常 202）就结束
                        if (r.expectId == null) return@takeWhile false
                    }
                    is HttpClientStreamChunk.Bytes -> { count(chunk.bytes.size); text(utf8.decode(chunk.bytes)) }
                    is HttpClientStreamChunk.Text -> { count(chunk.text.length); text(chunk.text) }
                    is HttpClientStreamChunk.End -> {
                        text(utf8.flush())
                        if (r.sse) events(framer.finish())
                        else if (body.isNotBlank()) for (m in parseBody(body.toString())) { dispatch(m, r); if (r.response != null) break }
                    }
                }
                r.response == null
            }.collect()
        } catch (e: HttpClientException) {
            throw httpError(e.error)
        }
    }

    /** 与本请求无关的消息（通知、服务端请求）交出去；其他 id 的响应忽略。 */
    private suspend fun dispatch(m: JsonObject, r: Reader) {
        if ("method" in m) { r.onInbound(m); return }
        val id = (m["id"] as? JsonPrimitive)?.intOrNull
        if (r.expectId != null && id == r.expectId && ("result" in m || "error" in m)) r.response = m
    }

    private fun parse(text: String): JsonObject =
        runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject
            ?: throw McpException(McpErrorKind.Protocol, "malformed JSON-RPC message from MCP server")

    private fun parseBody(text: String): List<JsonObject> {
        val e: JsonElement = runCatching { json.parseToJsonElement(text) }.getOrNull()
            ?: throw McpException(McpErrorKind.Protocol, "MCP server returned a non-JSON response")
        return when (e) {
            is JsonObject -> listOf(e)
            is JsonArray -> e.map { it as? JsonObject ?: throw McpException(McpErrorKind.Protocol, "malformed JSON-RPC batch from MCP server") }
            else -> throw McpException(McpErrorKind.Protocol, "malformed JSON-RPC message from MCP server")
        }
    }

    private fun httpError(err: HttpClientError): McpException = when (err) {
        is HttpClientError.Http -> {
            // 4xx 里常带 JSON-RPC 错误（如 modern 协议的版本错误），优先用它的错误码
            val rpc = err.body?.let { b -> runCatching { (json.parseToJsonElement(b) as? JsonObject)?.get("error") as? JsonObject }.getOrNull() }
            if (rpc != null) rpcError(rpc, err.statusCode)
            else McpException(McpErrorKind.Http, "MCP HTTP ${err.statusCode}", httpStatus = err.statusCode)
        }
        is HttpClientError.Timeout -> McpException(McpErrorKind.Timeout, err.message, outcomeUnknown = true, cause = err.cause)
        is HttpClientError.Network -> McpException(McpErrorKind.Transport, err.message, outcomeUnknown = true, cause = err.cause)
        is HttpClientError.Unknown -> McpException(McpErrorKind.Transport, err.message, outcomeUnknown = true, cause = err.cause)
    }

    companion object {
        const val MAX_RESUMES = 2

        fun rpcError(o: JsonObject, httpStatus: Int? = null) = McpException(
            McpErrorKind.Rpc, o["message"]?.jsonPrimitive?.contentOrNull ?: "MCP error",
            code = (o["code"] as? JsonPrimitive)?.intOrNull, httpStatus = httpStatus, data = o["data"],
        )
    }
}
