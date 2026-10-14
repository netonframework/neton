package neton.ai.mcp

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import neton.ai.AiToolDefinition
import neton.ai.AiToolException
import neton.ai.AiToolExecutor
import neton.ai.protocol.transport.SseFramer
import neton.core.http.HttpHeaders
import neton.http.client.HttpClient
import neton.http.client.HttpClientBody
import neton.http.client.HttpClientMethod
import neton.http.client.HttpClientRequest

data class McpTool(val name: String, val description: String?, val inputSchema: JsonObject)

data class McpToolResult(
    /** 文本内容拼接（非文本内容以类型占位）。 */
    val text: String,
    val isError: Boolean,
    val structuredContent: JsonElement? = null,
    val raw: JsonObject,
)

class McpException(val code: Int?, message: String) : RuntimeException(message)

/**
 * MCP 客户端（Streamable HTTP 传输，spec/ai.md §10.3）。
 *
 * - 每条 JSON-RPC 消息一个 POST，`Accept: application/json, text/event-stream`；服务端可以回 JSON，
 *   也可以回一段 SSE，从中取出对应 id 的响应；
 * - `initialize` 时协商协议版本，服务端返回的 `Mcp-Session-Id` 与协商后的版本随后续请求带上；
 * - HTTP 客户端由宿主提供并负责关闭（neton-http 的所有权约定）；[close] 只结束 MCP 会话。
 */
class McpClient(
    private val http: HttpClient,
    private val url: String,
    private val headers: Map<String, String> = emptyMap(),
    private val clientName: String = "neton-ai",
    private val clientVersion: String = "1",
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private var nextId = 1
    private var sessionId: String? = null
    var protocolVersion: String = PROTOCOL_VERSION
        private set
    private var initialized = false

    suspend fun initialize(): JsonObject {
        val result = rpc("initialize", buildJsonObject {
            put("protocolVersion", PROTOCOL_VERSION)
            put("capabilities", buildJsonObject { })
            put("clientInfo", buildJsonObject { put("name", clientName); put("version", clientVersion) })
        })
        result["protocolVersion"]?.jsonPrimitive?.contentOrNull?.let { protocolVersion = it }
        notify("notifications/initialized")
        initialized = true
        return result
    }

    private suspend fun ensureInitialized() { if (!initialized) initialize() }

    /** 列出全部工具（自动翻页）。 */
    suspend fun listTools(): List<McpTool> {
        ensureInitialized()
        val out = mutableListOf<McpTool>()
        var cursor: String? = null
        do {
            val result = rpc("tools/list", buildJsonObject { cursor?.let { put("cursor", it) } })
            result["tools"]?.jsonArray?.forEach { t ->
                val o = t.jsonObject
                out += McpTool(
                    o["name"]?.jsonPrimitive?.contentOrNull ?: return@forEach,
                    o["description"]?.jsonPrimitive?.contentOrNull,
                    o["inputSchema"] as? JsonObject ?: buildJsonObject { put("type", "object") },
                )
            }
            cursor = result["nextCursor"]?.jsonPrimitive?.contentOrNull
        } while (cursor != null)
        return out
    }

    suspend fun callTool(name: String, arguments: JsonObject): McpToolResult {
        ensureInitialized()
        val result = rpc("tools/call", buildJsonObject { put("name", name); put("arguments", arguments) })
        val text = result["content"]?.jsonArray?.joinToString("\n") { c ->
            val o = c.jsonObject
            when (o["type"]?.jsonPrimitive?.contentOrNull) {
                "text" -> o["text"]?.jsonPrimitive?.contentOrNull ?: ""
                "resource" -> (o["resource"] as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull ?: "[resource]"
                else -> "[${o["type"]?.jsonPrimitive?.contentOrNull ?: "content"}]"
            }
        } ?: ""
        return McpToolResult(text, result["isError"]?.jsonPrimitive?.contentOrNull == "true", result["structuredContent"], result)
    }

    /**
     * 把服务端的工具包装成 [AiToolDefinition]，可直接放进生成请求。[prefix] 用来区分多个服务端的同名工具。
     * 工具返回 isError 时以 [AiToolException] 交给模型（内容来自 MCP 服务端，属于可公开的工具输出）。
     */
    suspend fun toolDefinitions(prefix: String = ""): List<AiToolDefinition> = listTools().map { t ->
        AiToolDefinition(
            name = prefix + t.name,
            description = t.description ?: "",
            inputSchemaJson = t.inputSchema.toString(),
            executor = AiToolExecutor { args ->
                val parsed = runCatching { json.parseToJsonElement(args.ifBlank { "{}" }) as? JsonObject }.getOrNull()
                    ?: throw AiToolException("arguments must be a JSON object", "invalid_arguments")
                val r = callTool(t.name, parsed)
                if (r.isError) throw AiToolException(r.text.ifEmpty { "tool reported an error" }, "mcp_tool_error")
                r.structuredContent?.takeIf { r.text.isEmpty() }?.toString() ?: r.text
            },
        )
    }

    /** 结束会话（服务端支持时）。 */
    suspend fun close() {
        val sid = sessionId ?: return
        runCatching {
            http.request(HttpClientRequest(method = HttpClientMethod.Delete, url = url,
                headers = HttpHeaders.from(baseHeaders() + ("Mcp-Session-Id" to sid))))
        }
        sessionId = null
        initialized = false
    }

    private fun baseHeaders(): Map<String, String> = headers + mapOf(
        "Accept" to "application/json, text/event-stream",
        "MCP-Protocol-Version" to protocolVersion,
    )

    private suspend fun notify(method: String) {
        post(buildJsonObject { put("jsonrpc", "2.0"); put("method", method) }, expectId = null)
    }

    private suspend fun rpc(method: String, params: JsonObject): JsonObject {
        val id = mutex.withLock { nextId++ }
        val msg = buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("method", method); put("params", params) }
        val response = post(msg, expectId = id) ?: throw McpException(null, "no response to $method")
        response["error"]?.let { e ->
            val o = e.jsonObject
            throw McpException(o["code"]?.jsonPrimitive?.intOrNull, o["message"]?.jsonPrimitive?.contentOrNull ?: "MCP error")
        }
        return response["result"] as? JsonObject ?: JsonObject(emptyMap())
    }

    private suspend fun post(message: JsonObject, expectId: Int?): JsonObject? {
        val h = baseHeaders() + ("Content-Type" to "application/json") + listOfNotNull(sessionId?.let { "Mcp-Session-Id" to it })
        val res = http.request(HttpClientRequest(method = HttpClientMethod.Post, url = url,
            headers = HttpHeaders.from(h), body = HttpClientBody.Json(message.toString())))
        res.headers.get("Mcp-Session-Id")?.let { sessionId = it }
        if (res.statusCode == 404 && sessionId != null) {
            // 会话过期：按规范需要重新 initialize
            sessionId = null
            initialized = false
            throw McpException(404, "MCP session expired")
        }
        if (res.statusCode !in 200..299) throw McpException(res.statusCode, "MCP HTTP ${res.statusCode}")
        if (expectId == null || res.body.isBlank()) return null
        val contentType = res.headers.get("Content-Type") ?: ""
        val candidates: List<JsonObject> = if (contentType.startsWith("text/event-stream")) {
            val f = SseFramer()
            (f.feed(res.body) + f.finish()).mapNotNull { runCatching { json.parseToJsonElement(it.data) as? JsonObject }.getOrNull() }
        } else listOfNotNull(runCatching { json.parseToJsonElement(res.body) as? JsonObject }.getOrNull())
        return candidates.firstOrNull { (it["id"] as? JsonPrimitive)?.intOrNull == expectId }
    }

    companion object {
        /** 客户端首选的协议版本；以服务端在 initialize 中返回的为准。 */
        const val PROTOCOL_VERSION = "2025-11-25"
    }
}
