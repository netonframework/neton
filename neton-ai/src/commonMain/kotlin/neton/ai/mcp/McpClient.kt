package neton.ai.mcp

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import neton.ai.AiContent
import neton.ai.AiTool
import neton.ai.AiToolExecutor
import neton.ai.AiToolKind
import neton.ai.AiToolOutput
import neton.ai.protocol.ir.MediaSource
import neton.ai.protocol.transport.SseFramer
import neton.core.http.HttpHeaders
import neton.http.client.HttpClient
import neton.http.client.HttpClientBody
import neton.http.client.HttpClientMethod
import neton.http.client.HttpClientRequest

data class McpTool(
    val name: String,
    val description: String?,
    val inputSchema: JsonObject,
    val title: String? = null,
    /** 行为提示（readOnlyHint、destructiveHint、idempotentHint、openWorldHint），只是提示，不能当作安全保证。 */
    val annotations: JsonObject? = null,
)

data class McpResource(val uri: String, val name: String?, val description: String?, val mimeType: String?)

data class McpPrompt(val name: String, val description: String?, val arguments: List<String>)

data class McpToolResult(
    /** 文本内容拼接（非文本内容以类型占位）。 */
    val text: String,
    val isError: Boolean,
    val structuredContent: JsonElement? = null,
    val raw: JsonObject,
)

data class McpServerInfo(val name: String, val version: String)

/** [data] 是 JSON-RPC 错误的附加数据（如不支持的协议版本列表）。 */
class McpException(val code: Int?, message: String, val data: JsonElement? = null) : RuntimeException(message)

/** 协议世代：2026-07-28 起是无状态的 modern；之前是 initialize 握手 + 会话的 legacy。 */
enum class McpProtocolEra { Modern, Legacy }

/**
 * MCP 客户端（Streamable HTTP 传输，spec/ai.md §10.5）。协议版本按 MCP 规范最新版：
 *
 * - **modern（[LATEST_PROTOCOL_VERSION]）**：先发 `server/discover` 协商；之后每个请求无状态——
 *   不握手、不建会话，`params._meta` 携带协议版本、客户端能力与客户端信息；请求头带 `MCP-Protocol-Version`、
 *   `Mcp-Method`、`Mcp-Name`，以及工具 schema 里 `x-mcp-header` 标注的参数（`Mcp-Param-*`）。结果必须带 `resultType`。
 * - **legacy（回退，最高 [LATEST_LEGACY_PROTOCOL_VERSION]）**：服务端不认识 `server/discover` 时走
 *   `initialize` 握手，按服务端返回的版本与 `Mcp-Session-Id` 继续，[close] 时 DELETE 结束会话。
 * - 探测返回 modern 协议的错误码（-32020 / -32021 / -32022，如版本不受支持）时直接报错，不回退。
 *
 * 服务端可以回 JSON，也可以回一段 SSE，从中取出对应 id 的响应。HTTP 客户端由宿主提供并负责关闭。
 */
class McpClient(
    private val http: HttpClient,
    private val url: String,
    private val headers: Map<String, String> = emptyMap(),
    private val clientName: String = "neton-ai",
    private val clientVersion: String = "1",
    /** 先尝试 modern 协议的 `server/discover`；关掉则直接走 legacy 握手。 */
    private val protocolVersionDiscovery: Boolean = true,
    /** 客户端能力（随 initialize 或每个 modern 请求发送）。 */
    private val capabilities: JsonObject = JsonObject(emptyMap()),
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private var nextId = 1
    private var sessionId: String? = null
    private var connected = false
    private val headerBindings = HashMap<String, List<HeaderBinding>>()

    var era: McpProtocolEra = McpProtocolEra.Legacy
        private set
    var protocolVersion: String = LATEST_LEGACY_PROTOCOL_VERSION
        private set
    var serverInfo: McpServerInfo? = null
        private set
    var serverCapabilities: JsonObject = JsonObject(emptyMap())
        private set
    var instructions: String? = null
        private set

    /** 工具 schema 里 `x-mcp-header` 标注不合法、因而被忽略的工具（名称 → 原因）。 */
    val ignoredTools: Map<String, String> get() = _ignored
    private val _ignored = LinkedHashMap<String, String>()

    /** 建立连接：先探测 modern 协议，不支持时回退到 legacy 握手。调用其他方法时会自动连接。 */
    suspend fun connect() {
        if (connected) return
        if (protocolVersionDiscovery && discover()) { connected = true; return }
        initializeLegacy()
        connected = true
    }

    private suspend fun discover(): Boolean {
        era = McpProtocolEra.Modern
        protocolVersion = LATEST_PROTOCOL_VERSION
        val result = try {
            withTimeout(DISCOVERY_TIMEOUT_MS) { rpc("server/discover", JsonObject(emptyMap())) }
        } catch (e: McpException) {
            if (e.code in MODERN_PROTOCOL_ERROR_CODES) throw e
            null
        } catch (e: TimeoutCancellationException) {
            null
        }
        if (result == null) {
            era = McpProtocolEra.Legacy
            protocolVersion = LATEST_LEGACY_PROTOCOL_VERSION
            return false
        }
        val supported = result["supportedVersions"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
        if (protocolVersion !in supported) throw McpException(null, "server does not support MCP protocol version $protocolVersion (supports $supported)")
        serverCapabilities = result["capabilities"] as? JsonObject ?: JsonObject(emptyMap())
        instructions = result["instructions"]?.jsonPrimitive?.contentOrNull
        ((result["_meta"] as? JsonObject)?.get(META_SERVER_INFO) as? JsonObject)?.let { serverInfo = info(it) }
        return true
    }

    private suspend fun initializeLegacy() {
        era = McpProtocolEra.Legacy
        protocolVersion = LATEST_LEGACY_PROTOCOL_VERSION
        val result = rpc("initialize", buildJsonObject {
            put("protocolVersion", LATEST_LEGACY_PROTOCOL_VERSION)
            put("capabilities", capabilities)
            put("clientInfo", clientInfo())
        })
        val version = result["protocolVersion"]?.jsonPrimitive?.contentOrNull ?: LATEST_LEGACY_PROTOCOL_VERSION
        if (version !in SUPPORTED_PROTOCOL_VERSIONS) throw McpException(null, "server's MCP protocol version is not supported: $version")
        protocolVersion = version
        serverCapabilities = result["capabilities"] as? JsonObject ?: JsonObject(emptyMap())
        instructions = result["instructions"]?.jsonPrimitive?.contentOrNull
        (result["serverInfo"] as? JsonObject)?.let { serverInfo = info(it) }
        notify("notifications/initialized")
    }

    private fun info(o: JsonObject) = McpServerInfo(o["name"]?.jsonPrimitive?.contentOrNull ?: "", o["version"]?.jsonPrimitive?.contentOrNull ?: "")

    private fun clientInfo() = buildJsonObject { put("name", clientName); put("version", clientVersion) }

    private fun requireCapability(name: String) {
        if (name !in serverCapabilities) throw McpException(null, "MCP server does not support $name")
    }

    /** 列出全部工具（自动翻页）。modern 协议下 `x-mcp-header` 标注不合法的工具会被忽略（见 [ignoredTools]）。 */
    suspend fun listTools(): List<McpTool> {
        connect()
        requireCapability("tools")
        val out = mutableListOf<McpTool>()
        var cursor: String? = null
        headerBindings.clear()
        _ignored.clear()
        do {
            val result = rpc("tools/list", buildJsonObject { cursor?.let { put("cursor", it) } })
            result["tools"]?.jsonArray?.forEach { t ->
                val o = t.jsonObject
                val name = o["name"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                val schema = o["inputSchema"] as? JsonObject ?: buildJsonObject { put("type", "object") }
                if (era == McpProtocolEra.Modern) {
                    when (val b = McpHeaders.bindings(schema)) {
                        is McpHeaders.Bindings.Ok -> headerBindings[name] = b.bindings
                        is McpHeaders.Bindings.Invalid -> { _ignored[name] = b.reason; return@forEach }
                    }
                }
                val annotations = o["annotations"] as? JsonObject
                out += McpTool(
                    name, o["description"]?.jsonPrimitive?.contentOrNull, schema,
                    o["title"]?.jsonPrimitive?.contentOrNull ?: annotations?.get("title")?.jsonPrimitive?.contentOrNull,
                    annotations,
                )
            }
            cursor = result["nextCursor"]?.jsonPrimitive?.contentOrNull
        } while (cursor != null)
        return out
    }

    suspend fun callTool(name: String, arguments: JsonObject): McpToolResult {
        connect()
        requireCapability("tools")
        val result = rpc("tools/call", buildJsonObject { put("name", name); put("arguments", arguments) })
        val text = result["content"]?.jsonArray?.joinToString("\n") { c ->
            val o = c.jsonObject
            when (o["type"]?.jsonPrimitive?.contentOrNull) {
                "text" -> o["text"]?.jsonPrimitive?.contentOrNull ?: ""
                "resource" -> (o["resource"] as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull ?: "[resource]"
                else -> "[${o["type"]?.jsonPrimitive?.contentOrNull ?: "content"}]"
            }
        } ?: ""
        return McpToolResult(text, result["isError"]?.jsonPrimitive?.booleanOrNull == true, result["structuredContent"], result)
    }

    /**
     * 把服务端的工具包装成动态工具 [AiTool]，可直接放进生成请求。[prefix] 用来区分多个服务端的同名工具。
     * 结果：文本 → 文本；图片/音频 → 多模态内容；只有 structuredContent 时 → JSON；isError → 错误输出（服务端给的内容属于可公开的工具输出）。
     */
    suspend fun tools(prefix: String = ""): List<AiTool> = listTools().map { t ->
        AiTool(
            name = prefix + t.name,
            description = t.description,
            inputSchema = t.inputSchema,
            kind = AiToolKind.Dynamic,
            title = t.title,
            metadata = buildMap {
                put("mcp.toolName", t.name)
                t.annotations?.forEach { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { put("mcp.$k", it) } }
            },
            execute = AiToolExecutor { input, _ ->
                val args = input as? JsonObject ?: return@AiToolExecutor AiToolOutput.ErrorText("arguments must be a JSON object")
                toOutput(callTool(t.name, args))
            },
        )
    }

    private fun toOutput(r: McpToolResult): AiToolOutput {
        val content = r.raw["content"] as? JsonArray
        if (r.isError) return AiToolOutput.ErrorText(r.text.ifEmpty { "tool reported an error" })
        if (content.isNullOrEmpty()) return r.structuredContent?.let { AiToolOutput.Json(it) } ?: AiToolOutput.Text("")
        val parts = content.mapNotNull { c ->
            val o = c as? JsonObject ?: return@mapNotNull null
            when (o["type"]?.jsonPrimitive?.contentOrNull) {
                "text" -> AiContent.Text(o["text"]?.jsonPrimitive?.contentOrNull ?: "")
                "image" -> AiContent.Image(MediaSource.Base64(o["data"]?.jsonPrimitive?.contentOrNull ?: "", o["mimeType"]?.jsonPrimitive?.contentOrNull))
                "audio" -> AiContent.File(MediaSource.Base64(o["data"]?.jsonPrimitive?.contentOrNull ?: "", o["mimeType"]?.jsonPrimitive?.contentOrNull))
                else -> AiContent.Text(o.toString())
            }
        }
        return if (parts.all { it is AiContent.Text }) AiToolOutput.Text(r.text) else AiToolOutput.Content(parts)
    }

    /** 列出资源（自动翻页）。 */
    suspend fun listResources(): List<McpResource> {
        connect()
        requireCapability("resources")
        val out = mutableListOf<McpResource>()
        var cursor: String? = null
        do {
            val result = rpc("resources/list", buildJsonObject { cursor?.let { put("cursor", it) } })
            result["resources"]?.jsonArray?.forEach { r ->
                val o = r.jsonObject
                out += McpResource(o["uri"]?.jsonPrimitive?.contentOrNull ?: return@forEach, o["name"]?.jsonPrimitive?.contentOrNull,
                    o["description"]?.jsonPrimitive?.contentOrNull, o["mimeType"]?.jsonPrimitive?.contentOrNull)
            }
            cursor = result["nextCursor"]?.jsonPrimitive?.contentOrNull
        } while (cursor != null)
        return out
    }

    /** 读取资源：返回原始结果（`contents` 数组，text 或 blob）。 */
    suspend fun readResource(uri: String): JsonObject {
        connect()
        requireCapability("resources")
        return rpc("resources/read", buildJsonObject { put("uri", uri) })
    }

    suspend fun listPrompts(): List<McpPrompt> {
        connect()
        requireCapability("prompts")
        return rpc("prompts/list", buildJsonObject { })["prompts"]?.jsonArray?.mapNotNull { p ->
            val o = p.jsonObject
            McpPrompt(o["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null, o["description"]?.jsonPrimitive?.contentOrNull,
                o["arguments"]?.jsonArray?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull } ?: emptyList())
        } ?: emptyList()
    }

    /** 取得提示词模板展开后的消息（原始 `messages` 数组）。 */
    suspend fun getPrompt(name: String, arguments: Map<String, String> = emptyMap()): JsonObject {
        connect()
        requireCapability("prompts")
        return rpc("prompts/get", buildJsonObject {
            put("name", name)
            put("arguments", buildJsonObject { arguments.forEach { (k, v) -> put(k, v) } })
        })
    }

    /** 结束：legacy 协议有会话时 DELETE 通知服务端；modern 协议无状态，无需通知。 */
    suspend fun close() {
        val sid = sessionId
        if (era == McpProtocolEra.Legacy && sid != null) {
            runCatching {
                http.request(HttpClientRequest(method = HttpClientMethod.Delete, url = url,
                    headers = HttpHeaders.from(baseHeaders() + ("Mcp-Session-Id" to sid))))
            }
        }
        sessionId = null
        connected = false
    }

    private fun baseHeaders(): Map<String, String> = headers + mapOf(
        "Accept" to "application/json, text/event-stream",
        "MCP-Protocol-Version" to protocolVersion,
    )

    private suspend fun notify(method: String) {
        post(buildJsonObject { put("jsonrpc", "2.0"); put("method", method) }, expectId = null, extraHeaders = emptyMap())
    }

    private suspend fun rpc(method: String, params: JsonObject): JsonObject {
        val id = mutex.withLock { nextId++ }
        val modern = era == McpProtocolEra.Modern
        val finalParams = if (!modern) params else {
            val meta = (params["_meta"] as? JsonObject ?: JsonObject(emptyMap())) + mapOf(
                META_PROTOCOL_VERSION to JsonPrimitive(protocolVersion),
                META_CLIENT_CAPABILITIES to capabilities,
                META_CLIENT_INFO to clientInfo(),
            )
            JsonObject(params + ("_meta" to JsonObject(meta)))
        }
        val msg = buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("method", method); put("params", finalParams) }
        val extra = if (modern) standardHeaders(method, finalParams) else emptyMap()
        val response = post(msg, expectId = id, extraHeaders = extra) ?: throw McpException(null, "no response to $method")
        response["error"]?.let { e -> throw rpcError(e.jsonObject) }
        val result = response["result"] as? JsonObject ?: JsonObject(emptyMap())
        if (modern) {
            when (result["resultType"]?.jsonPrimitive?.contentOrNull) {
                null -> throw McpException(null, "modern MCP result for $method is missing resultType")
                "input_required" -> throw McpException(null, "MCP server requested additional input for $method; multi round-trip requests are not supported yet")
            }
        }
        return result
    }

    private fun rpcError(o: JsonObject) = McpException(
        (o["code"] as? JsonPrimitive)?.intOrNull, o["message"]?.jsonPrimitive?.contentOrNull ?: "MCP error", o["data"],
    )

    /** modern 协议的标准请求头：方法、对象名，以及工具参数头。 */
    private fun standardHeaders(method: String, params: JsonObject): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        out["Mcp-Method"] = method
        val name = when (method) {
            "resources/read" -> params["uri"]
            "tools/call", "prompts/get" -> params["name"]
            else -> null
        }?.let { (it as? JsonPrimitive)?.contentOrNull }
        name?.let { out["Mcp-Name"] = McpHeaders.encode(it) }
        if (method == "tools/call" && name != null) {
            val args = params["arguments"] as? JsonObject
            val bindings = headerBindings[name]
            if (args != null && !bindings.isNullOrEmpty()) out += McpHeaders.toolHeaders(bindings, args)
        }
        return out
    }

    private suspend fun post(message: JsonObject, expectId: Int?, extraHeaders: Map<String, String>): JsonObject? {
        val legacySession = if (era == McpProtocolEra.Legacy && message["method"]?.jsonPrimitive?.contentOrNull != "initialize") sessionId else null
        val h = baseHeaders() + ("Content-Type" to "application/json") + extraHeaders + listOfNotNull(legacySession?.let { "Mcp-Session-Id" to it })
        val res = http.request(HttpClientRequest(method = HttpClientMethod.Post, url = url,
            headers = HttpHeaders.from(h), body = HttpClientBody.Json(message.toString())))
        if (era == McpProtocolEra.Legacy) res.headers.get("Mcp-Session-Id")?.let { sessionId = it }
        if (res.statusCode !in 200..299) {
            // 服务端可能在 4xx 里给出 JSON-RPC 错误（如 modern 协议的版本错误），优先用它的错误码
            val rpc = runCatching { (json.parseToJsonElement(res.body) as? JsonObject)?.get("error") as? JsonObject }.getOrNull()
            if (rpc != null) throw rpcError(rpc)
            if (res.statusCode == 404 && legacySession != null) {
                // legacy 会话过期：按规范需要重新握手
                sessionId = null
                connected = false
                throw McpException(404, "MCP session expired")
            }
            throw McpException(res.statusCode, "MCP HTTP ${res.statusCode}")
        }
        if (expectId == null || res.body.isBlank()) return null
        val contentType = res.headers.get("Content-Type") ?: ""
        val candidates: List<JsonObject> = if (contentType.startsWith("text/event-stream")) {
            val f = SseFramer()
            (f.feed(res.body) + f.finish()).mapNotNull { runCatching { json.parseToJsonElement(it.data) as? JsonObject }.getOrNull() }
        } else listOfNotNull(runCatching { json.parseToJsonElement(res.body) as? JsonObject }.getOrNull())
        return candidates.firstOrNull { (it["id"] as? JsonPrimitive)?.intOrNull == expectId }
    }

    companion object {
        /** MCP 规范最新版本（modern：server/discover + 无状态请求）。 */
        const val LATEST_PROTOCOL_VERSION = "2026-07-28"
        /** legacy 握手协议的最高版本，探测失败时回退到它。 */
        const val LATEST_LEGACY_PROTOCOL_VERSION = "2025-11-25"
        val SUPPORTED_PROTOCOL_VERSIONS = listOf(LATEST_PROTOCOL_VERSION, LATEST_LEGACY_PROTOCOL_VERSION, "2025-06-18", "2025-03-26", "2024-11-05")

        /** modern 协议定义的错误码：探测时遇到它们说明服务端是 modern，但条件不满足，不能回退。 */
        val MODERN_PROTOCOL_ERROR_CODES = setOf(-32020, -32021, -32022)
        const val DISCOVERY_TIMEOUT_MS = 1_000L

        const val META_PROTOCOL_VERSION = "io.modelcontextprotocol/protocolVersion"
        const val META_CLIENT_CAPABILITIES = "io.modelcontextprotocol/clientCapabilities"
        const val META_CLIENT_INFO = "io.modelcontextprotocol/clientInfo"
        const val META_SERVER_INFO = "io.modelcontextprotocol/serverInfo"
    }
}

internal data class HeaderBinding(val headerName: String, val path: List<String>, val valueType: String)

/** modern 协议的请求头规则（`x-mcp-header` 参数头与值编码）。 */
internal object McpHeaders {
    private val HTTP_TOKEN = Regex("^[!#$%&'*+\\-.^_`|~0-9A-Za-z]+$")
    private val BASE64_SENTINEL = Regex("^=\\?base64\\?.*\\?=$")

    sealed interface Bindings {
        data class Ok(val bindings: List<HeaderBinding>) : Bindings
        data class Invalid(val reason: String) : Bindings
    }

    /**
     * 头值编码：可打印 ASCII、首尾无空白、且不像编码哨兵时原样发送；否则发送 `=?base64?<UTF-8 的 base64>?=`。
     */
    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    fun encode(value: String): String {
        val plain = value.all { it == '\t' || it.code in 0x20..0x7e }
        if (plain && value.trim() == value && !BASE64_SENTINEL.matches(value)) return value
        return "=?base64?${kotlin.io.encoding.Base64.encode(value.encodeToByteArray())}?="
    }

    /**
     * 从工具的 inputSchema 找出 `x-mcp-header` 标注：只能标在从根经 `properties` 静态可达的
     * boolean / integer / string 属性上，头名是 HTTP token 且不重复（不区分大小写）。任一不合法则整个工具无效。
     */
    fun bindings(schema: JsonObject): Bindings {
        val out = mutableListOf<HeaderBinding>()
        val names = HashSet<String>()
        var error: String? = null
        fun visit(v: JsonElement, path: List<String>, reachable: Boolean) {
            if (error != null) return
            if (v is JsonArray) { v.forEach { visit(it, path, false) }; return }
            if (v !is JsonObject) return
            v["x-mcp-header"]?.let { h ->
                val name = (h as? JsonPrimitive)?.takeIf { it.isString }?.content
                val type = (v["type"] as? JsonPrimitive)?.contentOrNull
                error = when {
                    !reachable || path.isEmpty() -> "x-mcp-header is not on a statically reachable property"
                    name == null || !HTTP_TOKEN.matches(name) -> "x-mcp-header must be a non-empty HTTP token"
                    !names.add(name.lowercase()) -> "x-mcp-header value \"$name\" is not unique"
                    type !in setOf("boolean", "integer", "string") -> "x-mcp-header can only annotate boolean, integer, or string properties"
                    else -> { out += HeaderBinding(name, path, type!!); null }
                }
            }
            for ((k, child) in v) {
                if (k == "x-mcp-header") continue
                if (k == "properties" && child is JsonObject) child.forEach { (p, s) -> visit(s, path + p, reachable) }
                else visit(child, path, false)
            }
        }
        visit(schema, emptyList(), true)
        return error?.let { Bindings.Invalid(it) } ?: Bindings.Ok(out)
    }

    /** 按绑定从参数取值生成 `Mcp-Param-<名>`；值类型不符时报错（参数缺失则不发送该头）。 */
    fun toolHeaders(bindings: List<HeaderBinding>, args: JsonObject): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (b in bindings) {
            var cur: JsonElement? = args
            for (seg in b.path) cur = (cur as? JsonObject)?.get(seg)
            val p = cur as? JsonPrimitive ?: continue
            if (p is kotlinx.serialization.json.JsonNull) continue
            val ok = when (b.valueType) {
                "string" -> p.isString
                "boolean" -> !p.isString && p.booleanOrNull != null
                else -> !p.isString && p.longOrNull != null
            }
            if (!ok) throw McpException(null, "tool argument \"${b.path.joinToString(".")}\" does not match its x-mcp-header type")
            out["Mcp-Param-${b.headerName}"] = encode(p.content)
        }
        return out
    }
}
