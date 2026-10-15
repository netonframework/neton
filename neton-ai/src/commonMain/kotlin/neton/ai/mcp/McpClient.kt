package neton.ai.mcp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
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
import neton.core.http.HttpHeaders
import neton.http.client.HttpClient
import neton.http.client.HttpClientMethod
import neton.http.client.HttpClientRequest
import kotlin.concurrent.Volatile
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * MCP 客户端（Streamable HTTP 传输，spec/ai.md §10.5）。协议版本按 MCP 规范最新版：
 *
 * - **modern（[LATEST_PROTOCOL_VERSION]）**：先发 `server/discover` 协商；之后每个请求无状态——
 *   不握手、不建会话，`params._meta` 携带协议版本、客户端能力与客户端信息；请求头带 `MCP-Protocol-Version`、
 *   `Mcp-Method`、`Mcp-Name`，以及工具 schema 里 `x-mcp-header` 标注的参数（`Mcp-Param-*`）。结果必须带 `resultType`。
 * - **legacy（回退，最高 [LATEST_LEGACY_PROTOCOL_VERSION]）**：探测得到"服务端不认识这个方法"一类的答复
 *   （JSON-RPC 错误、400/404/405/406/415/422、响应不合 modern 格式、1 秒内无响应）时走 `initialize` 握手，
 *   按服务端返回的版本与 `Mcp-Session-Id` 继续。鉴权失败、限流、5xx、网络错误、modern 协议错误码
 *   （-32020 / -32021 / -32022）原样报错，不回退。
 *
 * 生命周期：
 * - 连接是一份不可变快照（世代、版本、会话、能力）。并发的首次调用共用同一次协商；每个请求从头到尾用同一份快照。
 * - legacy 会话过期（带会话的请求收到 404）：只让发生过期的那一份快照失效，重新握手后把该请求重发一次
 *   ——404 说明服务端没有处理它。其他失败（超时、断流、5xx）不自动重发，结果可能未知（[McpException.outcomeUnknown]）。
 * - [close] 是终态：之后的调用报 [McpErrorKind.Closed]；已在途的请求不取消，按各自的快照完成或失败，不会重新连接。
 *
 * 工具目录（工具定义、`x-mcp-header` 绑定、被忽略的工具）是一份整体替换的快照：全部翻页成功才替换，失败保留旧的；
 * 并发刷新合并为一次。服务端给了 `ttlMs` 时在有效期内直接复用；收到 `notifications/tools/list_changed` 时作废。
 * 一个客户端实例对应一组凭证，缓存不跨实例共享。
 *
 * 服务端可以回 JSON 或 SSE；SSE 增量解析，等到对应 id 的响应即返回。响应前到达的通知交给 [onNotification]；
 * legacy 服务端发来的请求中 `ping` 回空结果，其他（sampling、elicitation、roots）回"不支持"。
 * HTTP 客户端由宿主提供并负责关闭。
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
    /** 单个请求的超时（含读取响应）。 */
    private val requestTimeoutMs: Long = 60_000,
    /** 单个响应体的大小上限。 */
    maxResponseBytes: Long = 16L * 1024 * 1024,
    /** 服务端通知回调；回调里的异常被忽略，不影响请求。 */
    private val onNotification: (suspend (McpNotification) -> Unit)? = null,
) {
    private class Connection(
        val era: McpProtocolEra,
        val protocolVersion: String,
        val sessionId: String?,
        val serverInfo: McpServerInfo?,
        val capabilities: JsonObject,
        val instructions: String?,
    )

    private class Catalog(
        val tools: List<McpTool>,
        /** 每个列出的工具都有一项（无标注为空列表）。 */
        val bindings: Map<String, List<HeaderBinding>>,
        val ignored: Map<String, String>,
        val expiresAt: TimeMark?,
    ) {
        fun fresh() = expiresAt?.hasPassedNow() == false
    }

    private val transport = McpHttpTransport(http, url, maxResponseBytes)
    private val lifecycle = Mutex()
    private val catalogLock = Mutex()
    private val idLock = Mutex()
    private var nextId = 1
    private var closed = false
    @Volatile private var conn: Connection? = null
    @Volatile private var catalog: Catalog? = null

    val era: McpProtocolEra? get() = conn?.era
    val protocolVersion: String? get() = conn?.protocolVersion
    val serverInfo: McpServerInfo? get() = conn?.serverInfo
    val serverCapabilities: JsonObject get() = conn?.capabilities ?: JsonObject(emptyMap())
    val instructions: String? get() = conn?.instructions

    /** 工具 schema 里 `x-mcp-header` 标注不合法、因而被忽略的工具（名称 → 原因），取自最近一次工具目录。 */
    val ignoredTools: Map<String, String> get() = catalog?.ignored ?: emptyMap()

    /** 建立连接：先探测 modern 协议，不支持时回退到 legacy 握手。调用其他方法时会自动连接。 */
    suspend fun connect() {
        connection()
    }

    private suspend fun connection(): Connection {
        conn?.let { return it }
        return lifecycle.withLock {
            if (closed) throw McpException(McpErrorKind.Closed, "MCP client is closed")
            conn ?: establish().also { conn = it }
        }
    }

    /** 只让过期的那一份快照失效：别的请求可能已经换上了新会话。 */
    private suspend fun invalidate(stale: Connection) {
        lifecycle.withLock { if (conn === stale) conn = null }
    }

    private suspend fun establish(): Connection =
        (if (protocolVersionDiscovery) discover() else null) ?: initializeLegacy()

    private suspend fun discover(): Connection? {
        val probe = Connection(McpProtocolEra.Modern, LATEST_PROTOCOL_VERSION, null, null, JsonObject(emptyMap()), null)
        val result = try {
            withTimeoutOrNull(DISCOVERY_TIMEOUT_MS) { exchange(probe, "server/discover", JsonObject(emptyMap())).first }
        } catch (e: McpException) {
            if (!fallsBack(e)) throw e
            null
        } ?: return null
        val supported = result["supportedVersions"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
        if (LATEST_PROTOCOL_VERSION !in supported) {
            throw McpException(McpErrorKind.Unsupported, "server does not support MCP protocol version $LATEST_PROTOCOL_VERSION (supports $supported)")
        }
        return Connection(
            McpProtocolEra.Modern, LATEST_PROTOCOL_VERSION, null,
            ((result["_meta"] as? JsonObject)?.get(META_SERVER_INFO) as? JsonObject)?.let(::info),
            result["capabilities"] as? JsonObject ?: JsonObject(emptyMap()),
            result["instructions"]?.jsonPrimitive?.contentOrNull,
        )
    }

    /** 探测失败时是否回退到 legacy：只有"服务端不认识 server/discover"一类的答复才回退。 */
    private fun fallsBack(e: McpException): Boolean {
        if (e.code in MODERN_PROTOCOL_ERROR_CODES) return false
        val status = e.httpStatus
        return when (e.kind) {
            McpErrorKind.Rpc -> status == null || (status !in NO_FALLBACK_STATUS && status < 500)
            McpErrorKind.Http -> status in FALLBACK_HTTP_STATUS
            McpErrorKind.Protocol -> true
            else -> false
        }
    }

    private suspend fun initializeLegacy(): Connection {
        val probe = Connection(McpProtocolEra.Legacy, LATEST_LEGACY_PROTOCOL_VERSION, null, null, JsonObject(emptyMap()), null)
        val (result, responseHeaders) = exchange(probe, "initialize", buildJsonObject {
            put("protocolVersion", LATEST_LEGACY_PROTOCOL_VERSION)
            put("capabilities", capabilities)
            put("clientInfo", clientInfo())
        })
        val version = result["protocolVersion"]?.jsonPrimitive?.contentOrNull ?: LATEST_LEGACY_PROTOCOL_VERSION
        if (version !in SUPPORTED_PROTOCOL_VERSIONS) throw McpException(McpErrorKind.Unsupported, "server's MCP protocol version is not supported: $version")
        val c = Connection(
            McpProtocolEra.Legacy, version, responseHeaders.get("Mcp-Session-Id"),
            (result["serverInfo"] as? JsonObject)?.let(::info),
            result["capabilities"] as? JsonObject ?: JsonObject(emptyMap()),
            result["instructions"]?.jsonPrimitive?.contentOrNull,
        )
        transport.send(
            buildJsonObject { put("jsonrpc", "2.0"); put("method", "notifications/initialized") },
            baseHeaders(c), expectId = null, resumable = false, onInbound = {},
        )
        return c
    }

    private fun info(o: JsonObject) = McpServerInfo(o["name"]?.jsonPrimitive?.contentOrNull ?: "", o["version"]?.jsonPrimitive?.contentOrNull ?: "")

    private fun clientInfo() = buildJsonObject { put("name", clientName); put("version", clientVersion) }

    private fun requireCapability(c: Connection, name: String) {
        if (name !in c.capabilities) throw McpException(McpErrorKind.Unsupported, "MCP server does not support $name")
    }

    // ───────────────────────── 工具 ─────────────────────────

    /**
     * 列出全部工具（自动翻页）。服务端给了 `ttlMs` 时，有效期内直接返回缓存。
     * modern 协议下 `x-mcp-header` 标注不合法的工具会被忽略（见 [ignoredTools]）。
     */
    suspend fun listTools(): List<McpTool> = refreshCatalog(force = false).tools

    private suspend fun refreshCatalog(force: Boolean): Catalog {
        val seen = catalog
        if (!force && seen != null && seen.fresh()) return seen
        return catalogLock.withLock {
            // 等锁期间别人已经刷新过：直接用它的结果
            catalog?.let { if (it !== seen) return@withLock it }
            fetchCatalog().also { catalog = it }
        }
    }

    private suspend fun fetchCatalog(): Catalog {
        val tools = mutableListOf<McpTool>()
        val bindings = HashMap<String, List<HeaderBinding>>()
        val ignored = LinkedHashMap<String, String>()
        var ttlMs: Long? = null
        paginate("tools/list", "tools") { page, t ->
            if (t == null) { if (ttlMs == null) ttlMs = page["ttlMs"]?.jsonPrimitive?.longOrNull; return@paginate }
            val o = t as? JsonObject ?: return@paginate
            val name = o["name"]?.jsonPrimitive?.contentOrNull ?: return@paginate
            val schema = o["inputSchema"] as? JsonObject ?: buildJsonObject { put("type", "object") }
            if (era == McpProtocolEra.Modern) {
                when (val b = McpHeaders.bindings(schema)) {
                    is McpHeaders.Bindings.Ok -> bindings[name] = b.bindings
                    is McpHeaders.Bindings.Invalid -> { ignored[name] = b.reason; return@paginate }
                }
            } else bindings[name] = emptyList()
            val annotations = o["annotations"] as? JsonObject
            tools += McpTool(
                name, o["description"]?.jsonPrimitive?.contentOrNull, schema,
                o["title"]?.jsonPrimitive?.contentOrNull ?: annotations?.get("title")?.jsonPrimitive?.contentOrNull,
                annotations,
            )
        }
        val expiresAt = ttlMs?.takeIf { it > 0 }?.let { TimeSource.Monotonic.markNow() + it.milliseconds }
        return Catalog(tools, bindings, ignored, expiresAt)
    }

    suspend fun callTool(name: String, arguments: JsonObject): McpToolResult {
        val params = buildJsonObject { put("name", name); put("arguments", arguments) }
        val result = request("tools/call", params, capability = "tools") { c ->
            if (c.era == McpProtocolEra.Modern) paramHeaders(name, arguments) else emptyMap()
        }
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

    /** 工具参数头需要工具 schema：目录里没有这个工具时刷新一次，被忽略的工具拒绝调用。 */
    private suspend fun paramHeaders(name: String, arguments: JsonObject): Map<String, String> {
        var cat = catalog
        if (cat == null || (name !in cat.bindings && name !in cat.ignored)) cat = refreshCatalog(force = true)
        cat.ignored[name]?.let { throw McpException(McpErrorKind.Unsupported, "MCP tool $name is ignored: $it") }
        val bindings = cat.bindings[name] ?: return emptyMap()
        return McpHeaders.toolHeaders(bindings, arguments)
    }

    /**
     * 把服务端的工具包装成动态工具 [AiTool]，可直接放进生成请求。[prefix] 用来区分多个服务端的同名工具。
     * 结果：文本 → 文本；图片/音频 → 多模态内容；只有 structuredContent 时 → JSON；isError → 错误输出（服务端给的内容属于可公开的工具输出）。
     * 调用结果未知（断流、超时）时告诉模型先确认再重试，而不是当作普通失败。
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
                try {
                    toOutput(callTool(t.name, args))
                } catch (e: McpException) {
                    if (!e.outcomeUnknown) throw e
                    AiToolOutput.ErrorText("""{"error":"outcome_unknown","message":"The connection to the tool server failed after the call was sent; it may or may not have run. Check its effect before retrying."}""")
                }
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

    // ───────────────────────── 资源与提示词 ─────────────────────────

    /** 列出资源（自动翻页）。 */
    suspend fun listResources(): List<McpResource> {
        val out = mutableListOf<McpResource>()
        paginate("resources/list", "resources") { _, r ->
            val o = r as? JsonObject ?: return@paginate
            out += McpResource(o["uri"]?.jsonPrimitive?.contentOrNull ?: return@paginate, o["name"]?.jsonPrimitive?.contentOrNull,
                o["description"]?.jsonPrimitive?.contentOrNull, o["mimeType"]?.jsonPrimitive?.contentOrNull)
        }
        return out
    }

    /** 读取资源：返回原始结果（`contents` 数组，text 或 blob）。 */
    suspend fun readResource(uri: String): JsonObject =
        request("resources/read", buildJsonObject { put("uri", uri) }, capability = "resources")

    /** 列出提示词模板（自动翻页）。 */
    suspend fun listPrompts(): List<McpPrompt> {
        val out = mutableListOf<McpPrompt>()
        paginate("prompts/list", "prompts") { _, p ->
            val o = p as? JsonObject ?: return@paginate
            out += McpPrompt(o["name"]?.jsonPrimitive?.contentOrNull ?: return@paginate, o["description"]?.jsonPrimitive?.contentOrNull,
                o["arguments"]?.jsonArray?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull } ?: emptyList())
        }
        return out
    }

    /** 取得提示词模板展开后的消息（原始 `messages` 数组）。 */
    suspend fun getPrompt(name: String, arguments: Map<String, String> = emptyMap()): JsonObject =
        request("prompts/get", buildJsonObject {
            put("name", name)
            put("arguments", buildJsonObject { arguments.forEach { (k, v) -> put(k, v) } })
        }, capability = "prompts")

    /** 翻页读取列表：每页先以 `(页, null)` 回调一次，再逐项回调。重复的游标说明服务端在兜圈子，报协议错误。 */
    private suspend fun paginate(method: String, key: String, each: (JsonObject, kotlinx.serialization.json.JsonElement?) -> Unit) {
        val capability = method.substringBefore('/')
        val seen = HashSet<String>()
        var cursor: String? = null
        do {
            val result = request(method, buildJsonObject { cursor?.let { put("cursor", it) } }, capability)
            each(result, null)
            result[key]?.jsonArray?.forEach { each(result, it) }
            cursor = result["nextCursor"]?.jsonPrimitive?.contentOrNull
            if (cursor != null && !seen.add(cursor)) throw McpException(McpErrorKind.Protocol, "MCP server repeated cursor $cursor for $method")
        } while (cursor != null)
    }

    /** 结束：之后的调用都报 [McpErrorKind.Closed]。legacy 协议有会话时 DELETE 通知服务端；modern 协议无状态，无需通知。 */
    suspend fun close() {
        val c = lifecycle.withLock {
            if (closed) return
            closed = true
            conn.also { conn = null }
        }
        catalog = null
        val sid = c?.sessionId ?: return
        if (c.era != McpProtocolEra.Legacy) return
        try {
            http.request(HttpClientRequest(method = HttpClientMethod.Delete, url = url,
                headers = HttpHeaders.from(baseHeaders(c) + ("Mcp-Session-Id" to sid))))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // 会话终止只是礼貌通知：服务端也会按超时回收
        }
    }

    // ───────────────────────── 请求 ─────────────────────────

    private fun baseHeaders(c: Connection): Map<String, String> = headers + mapOf(
        "Accept" to "application/json, text/event-stream",
        "MCP-Protocol-Version" to c.protocolVersion,
    ) + listOfNotNull(c.sessionId?.let { "Mcp-Session-Id" to it })

    /** 已连接后的请求：检查能力；legacy 会话过期时只让该快照失效，重新握手后重发一次。 */
    private suspend fun request(
        method: String,
        params: JsonObject,
        capability: String,
        extraHeaders: suspend (Connection) -> Map<String, String> = { emptyMap() },
    ): JsonObject {
        var c = connection()
        requireCapability(c, capability)
        try {
            return exchange(c, method, params, extraHeaders(c)).first
        } catch (e: McpException) {
            if (e.kind != McpErrorKind.SessionExpired) throw e
            invalidate(c)
        }
        c = connection()
        requireCapability(c, capability)
        return exchange(c, method, params, extraHeaders(c)).first
    }

    /** 用确定的连接快照收发一次。返回结果与响应头（握手要从中取会话 ID）。 */
    private suspend fun exchange(
        c: Connection,
        method: String,
        params: JsonObject,
        extraHeaders: Map<String, String> = emptyMap(),
    ): Pair<JsonObject, HttpHeaders> {
        val id = idLock.withLock { nextId++ }
        val modern = c.era == McpProtocolEra.Modern
        val finalParams = if (!modern) params else {
            val meta = (params["_meta"] as? JsonObject ?: JsonObject(emptyMap())) + mapOf(
                META_PROTOCOL_VERSION to JsonPrimitive(c.protocolVersion),
                META_CLIENT_CAPABILITIES to capabilities,
                META_CLIENT_INFO to clientInfo(),
            )
            JsonObject(params + ("_meta" to JsonObject(meta)))
        }
        val msg = buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("method", method); put("params", finalParams) }
        val headers = baseHeaders(c) + (if (modern) standardHeaders(method, finalParams) else emptyMap()) + extraHeaders
        val reply = try {
            withTimeoutOrNull(requestTimeoutMs) {
                transport.send(msg, headers, id, resumable = !modern) { inbound(c, it) }
            } ?: throw McpException(McpErrorKind.Timeout, "MCP $method timed out after ${requestTimeoutMs}ms", outcomeUnknown = true)
        } catch (e: McpException) {
            if (e.kind == McpErrorKind.Http && e.httpStatus == 404 && c.sessionId != null) {
                throw McpException(McpErrorKind.SessionExpired, "MCP session expired", httpStatus = 404, cause = e)
            }
            throw e
        }
        val response = reply.message ?: throw McpException(McpErrorKind.Protocol, "no response to $method")
        (response["error"] as? JsonObject)?.let { throw McpHttpTransport.rpcError(it) }
        val result = response["result"] as? JsonObject ?: JsonObject(emptyMap())
        if (modern) {
            when (result["resultType"]?.jsonPrimitive?.contentOrNull) {
                null -> throw McpException(McpErrorKind.Protocol, "modern MCP result for $method is missing resultType")
                "input_required" -> throw McpException(McpErrorKind.Unsupported, "MCP server requested additional input for $method; multi round-trip requests are not supported yet")
            }
        }
        return result to reply.headers
    }

    /** 响应之前到达的消息：通知交给回调，服务端请求立即答复。 */
    private suspend fun inbound(c: Connection, m: JsonObject) {
        val method = m["method"]?.jsonPrimitive?.contentOrNull ?: return
        val params = m["params"] as? JsonObject ?: JsonObject(emptyMap())
        val id = m["id"]
        if (id == null) {
            if (method == "notifications/tools/list_changed") catalog = null
            try {
                onNotification?.invoke(McpNotification(method, params))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
            }
            return
        }
        // 服务端请求：ping 回空结果；sampling、elicitation、roots 未声明支持，回"方法不存在"
        val reply = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            if (method == "ping") put("result", JsonObject(emptyMap()))
            else put("error", buildJsonObject { put("code", -32601); put("message", "client does not support $method") })
        }
        transport.send(reply, baseHeaders(c), expectId = null, resumable = false, onInbound = {})
    }

    /** modern 协议的标准请求头：方法与对象名。 */
    private fun standardHeaders(method: String, params: JsonObject): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        out["Mcp-Method"] = method
        when (method) {
            "resources/read" -> params["uri"]
            "tools/call", "prompts/get" -> params["name"]
            else -> null
        }?.let { (it as? JsonPrimitive)?.contentOrNull }?.let { out["Mcp-Name"] = McpHeaders.encode(it) }
        return out
    }

    companion object {
        /** MCP 规范最新版本（modern：server/discover + 无状态请求）。 */
        const val LATEST_PROTOCOL_VERSION = "2026-07-28"
        /** legacy 握手协议的最高版本，探测失败时回退到它。 */
        const val LATEST_LEGACY_PROTOCOL_VERSION = "2025-11-25"
        val SUPPORTED_PROTOCOL_VERSIONS = listOf(LATEST_PROTOCOL_VERSION, LATEST_LEGACY_PROTOCOL_VERSION, "2025-06-18", "2025-03-26", "2024-11-05")

        /** modern 协议定义的错误码：探测时遇到它们说明服务端是 modern，但条件不满足，不能回退。 */
        val MODERN_PROTOCOL_ERROR_CODES = setOf(-32020, -32021, -32022)
        /** 探测时这些 HTTP 状态说明服务端不认识该方法，可以回退。 */
        val FALLBACK_HTTP_STATUS = setOf(400, 404, 405, 406, 415, 422)
        /** 鉴权与限流：无论正文是什么都不回退。 */
        val NO_FALLBACK_STATUS = setOf(401, 403, 407, 429)
        const val DISCOVERY_TIMEOUT_MS = 1_000L

        const val META_PROTOCOL_VERSION = "io.modelcontextprotocol/protocolVersion"
        const val META_CLIENT_CAPABILITIES = "io.modelcontextprotocol/clientCapabilities"
        const val META_CLIENT_INFO = "io.modelcontextprotocol/clientInfo"
        const val META_SERVER_INFO = "io.modelcontextprotocol/serverInfo"
    }
}
