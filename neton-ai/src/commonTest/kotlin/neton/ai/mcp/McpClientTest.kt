package neton.ai.mcp

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import neton.ai.AiToolCall
import neton.ai.AiToolCallContext
import neton.ai.AiToolKind
import neton.ai.AiToolOutput
import neton.ai.testkit.bodyText
import neton.core.http.HttpHeaders
import neton.http.client.HttpClient
import neton.http.client.HttpClientCapability
import neton.http.client.HttpClientError
import neton.http.client.HttpClientException
import neton.http.client.HttpClientMethod
import neton.http.client.HttpClientRequest
import neton.http.client.HttpClientResponse
import neton.http.client.HttpClientStreamChunk
import neton.http.testkit.ScriptedHttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val URL = "https://mcp.example/mcp"

private fun HttpClientRequest.json(): JsonObject = Json.parseToJsonElement(bodyText()!!).jsonObject
private fun HttpClientRequest.rpcMethod(): String? = if (method == HttpClientMethod.Post) json()["method"]?.jsonPrimitive?.content else null
private fun ScriptedHttpClient.methods() = recorded.mapNotNull { it.rpcMethod() }
private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

private fun jsonReply(body: String, vararg headers: Pair<String, String>) =
    HttpClientResponse(200, HttpHeaders.of("Content-Type" to "application/json", *headers), body)

private fun sseReply(body: String) = HttpClientResponse(200, HttpHeaders.of("Content-Type" to "text/event-stream"), body)

/** 把"请求 → 完整响应"的脚本接到 stream() 上：非 2xx 按 HTTP 客户端契约抛 Http 错误。 */
private fun mcpServer(handler: suspend (HttpClientRequest, JsonObject, String?) -> HttpClientResponse): ScriptedHttpClient =
    ScriptedHttpClient().onStream(HttpClientMethod.Post, URL) { req ->
        val msg = req.json()
        // 客户端发回的 JSON-RPC 答复（回应服务端请求）：收下即可
        if ("method" !in msg) return@onStream listOf(HttpClientStreamChunk.Start(202, HttpHeaders.EMPTY), HttpClientStreamChunk.End(HttpHeaders.EMPTY))
        val r = handler(req, msg, msg["id"]?.jsonPrimitive?.content)
        if (r.statusCode !in 200..299) throw HttpClientException(HttpClientError.Http(r.statusCode, "HTTP ${r.statusCode}", r.body))
        listOf(HttpClientStreamChunk.Start(r.statusCode, r.headers), HttpClientStreamChunk.Bytes(r.body.encodeToByteArray()), HttpClientStreamChunk.End(r.headers))
    }

/** 可以在任意位置挂起的流式替身（模拟响应已到但连接未结束、或一直不回）。 */
private class FakeStreamClient(private val respond: suspend FlowCollector<HttpClientStreamChunk>.(HttpClientRequest, JsonObject) -> Unit) : HttpClient {
    val recorded = mutableListOf<HttpClientRequest>()
    override suspend fun request(request: HttpClientRequest): HttpClientResponse = error("unused")
    override fun stream(request: HttpClientRequest): Flow<HttpClientStreamChunk> = flow {
        recorded += request
        respond(request, request.json())
    }
    override suspend fun close() {}
    override val capabilities: Set<HttpClientCapability> = HttpClientCapability.entries.toSet()
}

private suspend fun FlowCollector<HttpClientStreamChunk>.emitJson(body: String) {
    val h = HttpHeaders.of("Content-Type" to "application/json")
    emit(HttpClientStreamChunk.Start(200, h)); emit(HttpClientStreamChunk.Bytes(body.encodeToByteArray())); emit(HttpClientStreamChunk.End(h))
}

private const val DISCOVER_OK = """{"resultType":"complete","supportedVersions":["2026-07-28","2025-11-25"],
    "capabilities":{"tools":{}},"instructions":"be nice","_meta":{"io.modelcontextprotocol/serverInfo":{"name":"m","version":"2"}}}"""

private val ctx = AiToolCallContext(AiToolCall("c", "add", "{}"), 1, null, emptyMap(), emptyList())

class McpClientTest {

    // ───────────────────────── legacy 回退 ─────────────────────────

    private fun legacyServer(): ScriptedHttpClient = mcpServer { _, msg, id ->
        when (msg["method"]!!.jsonPrimitive.content) {
            "initialize" -> jsonReply("""{"jsonrpc":"2.0","id":$id,"result":{"protocolVersion":"2025-06-18","capabilities":{"tools":{}},"serverInfo":{"name":"t","version":"1"}}}""", "Mcp-Session-Id" to "S1")
            "notifications/initialized" -> HttpClientResponse(202, HttpHeaders.of(), "")
            "tools/list" -> {
                val cursor = msg["params"]?.jsonObject?.get("cursor")?.jsonPrimitive?.content
                jsonReply(if (cursor == null) """{"jsonrpc":"2.0","id":$id,"result":{"tools":[{"name":"add","description":"add","inputSchema":{"type":"object"}}],"nextCursor":"p2"}}"""
                    else """{"jsonrpc":"2.0","id":$id,"result":{"tools":[{"name":"fail","inputSchema":{"type":"object"}}]}}""")
            }
            "tools/call" -> {
                val name = msg["params"]!!.jsonObject["name"]!!.jsonPrimitive.content
                val result = if (name == "add") """{"content":[{"type":"text","text":"3"}]}""" else """{"content":[{"type":"text","text":"bad input"}],"isError":true}"""
                // 以 SSE 返回，前面先来一条通知
                sseReply("data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\",\"params\":{\"progress\":1}}\n\n" +
                    "data: {\"jsonrpc\":\"2.0\",\"id\":$id,\"result\":$result}\n\n")
            }
            else -> HttpClientResponse(400, HttpHeaders.of(), "")
        }
    }

    @Test
    fun fallsBackToLegacyAndUsesTheSession() = runTest {
        val http = legacyServer()
        val notes = mutableListOf<McpNotification>()
        val mcp = McpClient(http, URL, mapOf("Authorization" to "Bearer t"), onNotification = { notes += it })
        val tools = mcp.tools(prefix = "calc_")
        assertEquals(listOf("calc_add", "calc_fail"), tools.map { it.name })
        assertEquals(AiToolKind.Dynamic, tools[0].kind)
        assertEquals(McpProtocolEra.Legacy, mcp.era)
        assertEquals("2025-06-18", mcp.protocolVersion)
        assertEquals(AiToolOutput.Text("3"), tools[0].execute!!.execute(Json.parseToJsonElement("""{"a":1,"b":2}"""), ctx))
        assertEquals(AiToolOutput.ErrorText("bad input"), tools[1].execute!!.execute(Json.parseToJsonElement("{}"), ctx))
        assertEquals(listOf("notifications/progress", "notifications/progress"), notes.map { it.method })
        // 握手之后的请求带会话 ID 与协商后的版本
        val later = http.recorded.last()
        assertEquals("S1", later.headers.get("Mcp-Session-Id"))
        assertEquals("2025-06-18", later.headers.get("MCP-Protocol-Version"))
        assertEquals(listOf("server/discover", "initialize", "notifications/initialized"), http.methods().take(3))
        assertNull(http.recorded.first().headers.get("Mcp-Session-Id"))
    }

    @Test
    fun authRateLimitAndServerErrorsDoNotFallBack() = runTest {
        for (status in listOf(401, 429, 503)) {
            val http = mcpServer { _, _, _ -> HttpClientResponse(status, HttpHeaders.of(), "nope") }
            val e = assertFailsWith<McpException> { McpClient(http, URL).connect() }
            assertEquals(McpErrorKind.Http, e.kind)
            assertEquals(status, e.httpStatus)
            assertEquals(listOf("server/discover"), http.methods(), "status $status must not trigger the legacy handshake")
        }
    }

    @Test
    fun legacySessionExpiryInvalidatesOnlyThatSessionAndResendsOnce() = runTest {
        var sessions = 0
        val http = mcpServer { req, msg, id ->
            when (msg["method"]!!.jsonPrimitive.content) {
                "server/discover" -> HttpClientResponse(404, HttpHeaders.of(), "")
                "initialize" -> { sessions++; jsonReply("""{"jsonrpc":"2.0","id":$id,"result":{"protocolVersion":"2025-11-25","capabilities":{"tools":{}}}}""", "Mcp-Session-Id" to "S$sessions") }
                "notifications/initialized" -> HttpClientResponse(202, HttpHeaders.of(), "")
                else -> if (req.headers.get("Mcp-Session-Id") == "S1") HttpClientResponse(404, HttpHeaders.of(), "")
                    else jsonReply("""{"jsonrpc":"2.0","id":$id,"result":{"tools":[]}}""")
            }
        }
        val mcp = McpClient(http, URL)
        assertEquals(emptyList(), mcp.listTools())
        assertEquals(2, sessions)
        assertEquals(listOf("tools/list", "tools/list"), http.methods().filter { it == "tools/list" })
        assertEquals("S2", http.recorded.last().headers.get("Mcp-Session-Id"))
    }

    @Test
    fun legacyStreamsResumeWithLastEventId() = runTest {
        val http = mcpServer { _, msg, id ->
            when (msg["method"]!!.jsonPrimitive.content) {
                "initialize" -> jsonReply("""{"jsonrpc":"2.0","id":$id,"result":{"protocolVersion":"2025-11-25","capabilities":{"tools":{}}}}""", "Mcp-Session-Id" to "S1")
                "notifications/initialized" -> HttpClientResponse(202, HttpHeaders.of(), "")
                // 只给了一个带 id 的预备事件就断开
                "tools/call" -> sseReply("id: e1\ndata: \n\n")
                else -> HttpClientResponse(400, HttpHeaders.of(), "")
            }
        }.onStream(HttpClientMethod.Get, URL) { req ->
            assertEquals("e1", req.headers.get("Last-Event-ID"))
            assertEquals("S1", req.headers.get("Mcp-Session-Id"))
            val h = HttpHeaders.of("Content-Type" to "text/event-stream")
            listOf(HttpClientStreamChunk.Start(200, h),
                HttpClientStreamChunk.Bytes("id: e2\ndata: {\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]}}\n\n".encodeToByteArray()),
                HttpClientStreamChunk.End(h))
        }
        val mcp = McpClient(http, URL, protocolVersionDiscovery = false)
        assertEquals("ok", mcp.callTool("t", obj("{}")).text)
    }

    @Test
    fun closeIsTerminalAndEndsTheLegacySession() = runTest {
        val http = legacyServer().on(HttpClientMethod.Delete, URL) { HttpClientResponse(204, HttpHeaders.of(), "") }
        val mcp = McpClient(http, URL)
        mcp.connect()
        mcp.close()
        assertEquals("S1", http.recorded.last { it.method == HttpClientMethod.Delete }.headers.get("Mcp-Session-Id"))
        assertEquals(McpErrorKind.Closed, assertFailsWith<McpException> { mcp.listTools() }.kind)
        mcp.close()
        assertEquals(1, http.recorded.count { it.method == HttpClientMethod.Delete })
    }

    // ───────────────────────── modern 协议 ─────────────────────────

    private val toolSchema = """{"type":"object","properties":{"region":{"type":"string","x-mcp-header":"Region"},
        "dry":{"type":"boolean","x-mcp-header":"Dry-Run"},"q":{"type":"string"}}}"""

    private fun modernServer(
        toolsList: (String?, Int) -> HttpClientResponse = { id, _ -> jsonReply("""{"jsonrpc":"2.0","id":$id,"result":{"resultType":"complete","tools":[{"name":"search","inputSchema":$toolSchema},
            {"name":"broken","inputSchema":{"type":"object","properties":{"o":{"type":"object","x-mcp-header":"Obj"}}}}]}}""") },
        discover: suspend (String?) -> HttpClientResponse = { id -> jsonReply("""{"jsonrpc":"2.0","id":$id,"result":$DISCOVER_OK}""") },
    ): ScriptedHttpClient {
        var lists = 0
        return mcpServer { _, msg, id ->
            fun ok(result: String) = jsonReply("""{"jsonrpc":"2.0","id":$id,"result":$result}""")
            when (msg["method"]!!.jsonPrimitive.content) {
                "server/discover" -> discover(id)
                "tools/list" -> toolsList(id, ++lists)
                "tools/call" -> if (msg["params"]!!.jsonObject["name"]!!.jsonPrimitive.content == "search") ok("""{"resultType":"complete","content":[{"type":"text","text":"hit"}]}""")
                    else ok("""{"resultType":"input_required","inputRequests":{}}""")
                else -> HttpClientResponse(400, HttpHeaders.of(), "")
            }
        }
    }

    @Test
    fun modernProtocolIsStatelessAndCarriesMetaAndHeaders() = runTest {
        val http = modernServer()
        val mcp = McpClient(http, URL)
        val tools = mcp.tools()
        assertEquals(McpProtocolEra.Modern, mcp.era)
        assertEquals("2026-07-28", mcp.protocolVersion)
        assertEquals(McpServerInfo("m", "2"), mcp.serverInfo)
        assertEquals("be nice", mcp.instructions)
        // 标注不合法的工具被忽略，也不能被直接调用
        assertEquals(listOf("search"), tools.map { it.name })
        assertTrue(mcp.ignoredTools.getValue("broken").contains("boolean, integer, or string"))
        assertEquals(McpErrorKind.Unsupported, assertFailsWith<McpException> { mcp.callTool("broken", obj("{}")) }.kind)

        mcp.callTool("search", obj("""{"region":"東京","dry":true,"q":"x"}"""))
        val call = http.recorded.last()
        assertEquals("2026-07-28", call.headers.get("MCP-Protocol-Version"))
        assertEquals("tools/call", call.headers.get("Mcp-Method"))
        assertEquals("search", call.headers.get("Mcp-Name"))
        assertEquals("=?base64?5p2x5Lqs?=", call.headers.get("Mcp-Param-Region"))
        assertEquals("true", call.headers.get("Mcp-Param-Dry-Run"))
        assertTrue(http.recorded.none { it.headers.get("Mcp-Session-Id") != null })
        val meta = call.json()["params"]!!.jsonObject["_meta"]!!.jsonObject
        assertEquals("2026-07-28", meta["io.modelcontextprotocol/protocolVersion"]!!.jsonPrimitive.content)
        assertEquals("neton-ai", meta["io.modelcontextprotocol/clientInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertTrue("initialize" !in http.methods())

        assertEquals(McpErrorKind.InvalidArgument, assertFailsWith<McpException> { mcp.callTool("search", obj("""{"dry":"yes"}""")) }.kind)
        val e = assertFailsWith<McpException> { mcp.callTool("other", obj("{}")) }
        assertTrue(e.message!!.contains("multi round-trip"))
        // 没有声明的能力不发请求
        assertEquals(McpErrorKind.Unsupported, assertFailsWith<McpException> { mcp.listPrompts() }.kind)
    }

    @Test
    fun directCallToolLoadsHeaderMetadataFirst() = runTest {
        val http = modernServer()
        McpClient(http, URL).callTool("search", obj("""{"region":"eu"}"""))
        assertEquals(listOf("server/discover", "tools/list", "tools/call"), http.methods())
        assertEquals("eu", http.recorded.last().headers.get("Mcp-Param-Region"))
    }

    @Test
    fun concurrentFirstCallsShareOneNegotiation() = runTest {
        val http = modernServer(discover = { id -> delay(50); jsonReply("""{"jsonrpc":"2.0","id":$id,"result":$DISCOVER_OK}""") })
        val mcp = McpClient(http, URL)
        (1..5).map { async { mcp.listTools() } }.awaitAll()
        assertEquals(1, http.methods().count { it == "server/discover" })
    }

    @Test
    fun aFailedRefreshKeepsThePreviousCatalog() = runTest {
        val http = modernServer(toolsList = { id, n ->
            if (n == 1) jsonReply("""{"jsonrpc":"2.0","id":$id,"result":{"resultType":"complete","tools":[{"name":"search","inputSchema":$toolSchema}],"nextCursor":"p2"}}""")
            else if (n == 2) jsonReply("""{"jsonrpc":"2.0","id":$id,"result":{"resultType":"complete","tools":[]}}""")
            else HttpClientResponse(500, HttpHeaders.of(), "")
        })
        val mcp = McpClient(http, URL)
        assertEquals(listOf("search"), mcp.listTools().map { it.name })
        assertEquals(500, assertFailsWith<McpException> { mcp.listTools() }.httpStatus)
        // 旧目录仍在：调用还能带上参数头，不会重新拉取
        mcp.callTool("search", obj("""{"region":"eu"}"""))
        assertEquals("eu", http.recorded.last().headers.get("Mcp-Param-Region"))
    }

    @Test
    fun catalogHonoursTtl() = runTest {
        val http = modernServer(toolsList = { id, _ -> jsonReply("""{"jsonrpc":"2.0","id":$id,"result":{"resultType":"complete","ttlMs":600000,"tools":[]}}""") })
        val mcp = McpClient(http, URL)
        mcp.listTools(); mcp.listTools()
        assertEquals(1, http.methods().count { it == "tools/list" })
    }

    @Test
    fun repeatedCursorsAreAProtocolError() = runTest {
        val http = modernServer(toolsList = { id, _ -> jsonReply("""{"jsonrpc":"2.0","id":$id,"result":{"resultType":"complete","tools":[],"nextCursor":"same"}}""") })
        assertEquals(McpErrorKind.Protocol, assertFailsWith<McpException> { McpClient(http, URL).listTools() }.kind)
    }

    @Test
    fun modernProtocolErrorsAreNotFallenBackFrom() = runTest {
        val http = modernServer(discover = { id ->
            HttpClientResponse(400, HttpHeaders.of("Content-Type" to "application/json"),
                """{"jsonrpc":"2.0","id":$id,"error":{"code":-32022,"message":"unsupported protocol version","data":{"supported":["2027-01-01"]}}}""")
        })
        val e = assertFailsWith<McpException> { McpClient(http, URL).listTools() }
        assertEquals(McpErrorKind.Rpc, e.kind)
        assertEquals(-32022, e.code)
        assertEquals(1, http.recorded.size)
    }

    @Test
    fun discoveryMustListTheModernVersionAndMalformedResultsFallBack() = runTest {
        val unsupported = modernServer(discover = { id -> jsonReply("""{"jsonrpc":"2.0","id":$id,"result":{"resultType":"complete","supportedVersions":["2027-01-01"],"capabilities":{}}}""") })
        assertEquals(McpErrorKind.Unsupported, assertFailsWith<McpException> { McpClient(unsupported, URL).connect() }.kind)
        // 缺 resultType 不是 modern 服务端：回退握手（这台测试服务端不支持握手，于是失败）
        val noType = modernServer(discover = { id -> jsonReply("""{"jsonrpc":"2.0","id":$id,"result":{"supportedVersions":["2026-07-28"],"capabilities":{}}}""") })
        assertFailsWith<McpException> { McpClient(noType, URL).connect() }
        assertEquals(listOf("server/discover", "initialize"), noType.methods())
    }

    // ───────────────────────── 传输 ─────────────────────────

    @Test
    fun sseResponsesReturnWithoutWaitingForTheStreamToEnd() = runTest {
        val http = FakeStreamClient { _, msg ->
            val id = msg["id"]?.jsonPrimitive?.content
            when (msg["method"]!!.jsonPrimitive.content) {
                "server/discover" -> emitJson("""{"jsonrpc":"2.0","id":$id,"result":$DISCOVER_OK}""")
                else -> {
                    emit(HttpClientStreamChunk.Start(200, HttpHeaders.of("Content-Type" to "text/event-stream")))
                    emit(HttpClientStreamChunk.Bytes("data: {\"jsonrpc\":\"2.0\",\"id\":$id,\"result\":{\"resultType\":\"complete\",\"tools\":[]}}\n\n".encodeToByteArray()))
                    awaitCancellation()
                }
            }
        }
        assertEquals(emptyList(), McpClient(http, URL, requestTimeoutMs = 5_000).listTools())
    }

    @Test
    fun serverRequestsAreAnsweredAndListChangedDropsTheCatalog() = runTest {
        var lists = 0
        val http = modernServer(toolsList = { id, _ ->
            lists++
            sseReply("data: {\"jsonrpc\":\"2.0\",\"id\":\"s1\",\"method\":\"ping\"}\n\n" +
                "data: {\"jsonrpc\":\"2.0\",\"id\":\"s2\",\"method\":\"sampling/createMessage\",\"params\":{}}\n\n" +
                "data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/tools/list_changed\"}\n\n" +
                "data: {\"jsonrpc\":\"2.0\",\"id\":$id,\"result\":{\"resultType\":\"complete\",\"ttlMs\":600000,\"tools\":[]}}\n\n")
        })
        val mcp = McpClient(http, URL)
        mcp.listTools()
        val replies = http.recorded.map { it.json() }.filter { "method" !in it }
        assertEquals("s1", replies[0]["id"]!!.jsonPrimitive.content)
        assertEquals(JsonObject(emptyMap()), replies[0]["result"])
        assertEquals("-32601", replies[1]["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
        // list_changed 在响应之前到达，作废的是旧目录；本次结果照常写入并在 TTL 内复用
        mcp.listTools()
        assertEquals(1, lists)
    }

    @Test
    fun timeoutsAreTypedAndToolCallsReportAnUnknownOutcome() = runTest {
        val http = FakeStreamClient { _, msg ->
            val id = msg["id"]?.jsonPrimitive?.content
            when (msg["method"]!!.jsonPrimitive.content) {
                "server/discover" -> emitJson("""{"jsonrpc":"2.0","id":$id,"result":$DISCOVER_OK}""")
                "tools/list" -> emitJson("""{"jsonrpc":"2.0","id":$id,"result":{"resultType":"complete","tools":[{"name":"slow","inputSchema":{"type":"object"}}]}}""")
                else -> { emit(HttpClientStreamChunk.Start(200, HttpHeaders.of("Content-Type" to "text/event-stream"))); awaitCancellation() }
            }
        }
        val mcp = McpClient(http, URL, requestTimeoutMs = 1_000)
        val e = assertFailsWith<McpException> { mcp.callTool("slow", obj("{}")) }
        assertEquals(McpErrorKind.Timeout, e.kind)
        assertTrue(e.outcomeUnknown)
        val out = mcp.tools().single().execute!!.execute(obj("{}"), ctx)
        assertTrue(out.asText().contains("outcome_unknown"))
    }

    @Test
    fun oversizedResponsesAreRejected() = runTest {
        val http = modernServer(toolsList = { id, _ -> jsonReply("""{"jsonrpc":"2.0","id":$id,"result":{"resultType":"complete","tools":[],"pad":"${"x".repeat(500)}"}}""") })
        val e = assertFailsWith<McpException> { McpClient(http, URL, maxResponseBytes = 400).listTools() }
        assertEquals(McpErrorKind.Protocol, e.kind)
    }

    // ───────────────────────── 请求头规则 ─────────────────────────

    @Test
    fun headerRules() {
        assertEquals("plain-value", McpHeaders.encode("plain-value"))
        assertEquals("=?base64?IHg=?=", McpHeaders.encode(" x"))
        assertEquals("=?base64?PT9iYXNlNjQ/YWJjPz0=?=", McpHeaders.encode("=?base64?abc?="))
        assertTrue(McpHeaders.bindings(obj("""{"type":"object","properties":{"a":{"type":"string","x-mcp-header":"X"},"b":{"type":"integer","x-mcp-header":"x"}}}""")) is McpHeaders.Bindings.Invalid)
        assertTrue(McpHeaders.bindings(obj("""{"type":"object","properties":{"a":{"type":"string","x-mcp-header":"bad name"}}}""")) is McpHeaders.Bindings.Invalid)
        assertTrue(McpHeaders.bindings(obj("""{"type":"object","anyOf":[{"properties":{"a":{"type":"string","x-mcp-header":"A"}}}]}""")) is McpHeaders.Bindings.Invalid)
        val nested = McpHeaders.bindings(obj("""{"type":"object","properties":{"o":{"type":"object","properties":{"n":{"type":"integer","x-mcp-header":"N"}}}}}"""))
        val b = (nested as McpHeaders.Bindings.Ok).bindings.single()
        assertEquals(listOf("o", "n"), b.path)
        assertEquals(mapOf("Mcp-Param-N" to "5"), McpHeaders.toolHeaders(listOf(b), obj("""{"o":{"n":5}}""")))
    }
}
