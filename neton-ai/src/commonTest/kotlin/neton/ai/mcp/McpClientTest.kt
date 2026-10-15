package neton.ai.mcp

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import neton.ai.testkit.bodyText
import neton.core.http.HttpHeaders
import neton.http.client.HttpClientMethod
import neton.http.client.HttpClientResponse
import neton.http.testkit.ScriptedHttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpClientTest {
    private fun server(): ScriptedHttpClient = ScriptedHttpClient()
        .on(HttpClientMethod.Post, "https://mcp.example/mcp") { req ->
            val msg = Json.parseToJsonElement(req.bodyText()!!).jsonObject
            val id = msg["id"]?.jsonPrimitive?.content
            when (msg["method"]!!.jsonPrimitive.content) {
                "initialize" -> HttpClientResponse(200, HttpHeaders.of("Content-Type" to "application/json", "Mcp-Session-Id" to "S1"),
                    """{"jsonrpc":"2.0","id":$id,"result":{"protocolVersion":"2025-06-18","capabilities":{"tools":{}},"serverInfo":{"name":"t","version":"1"}}}""")
                "notifications/initialized" -> HttpClientResponse(202, HttpHeaders.of(), "")
                "tools/list" -> {
                    val cursor = msg["params"]?.jsonObject?.get("cursor")?.jsonPrimitive?.content
                    val body = if (cursor == null) """{"jsonrpc":"2.0","id":$id,"result":{"tools":[{"name":"add","description":"add","inputSchema":{"type":"object"}}],"nextCursor":"p2"}}"""
                        else """{"jsonrpc":"2.0","id":$id,"result":{"tools":[{"name":"fail","inputSchema":{"type":"object"}}]}}"""
                    HttpClientResponse(200, HttpHeaders.of("Content-Type" to "application/json"), body)
                }
                "tools/call" -> {
                    val name = msg["params"]!!.jsonObject["name"]!!.jsonPrimitive.content
                    val result = if (name == "add") """{"content":[{"type":"text","text":"3"}]}""" else """{"content":[{"type":"text","text":"bad input"}],"isError":true}"""
                    // 以 SSE 返回，前面先来一条无关的通知
                    HttpClientResponse(200, HttpHeaders.of("Content-Type" to "text/event-stream"),
                        "data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\",\"params\":{}}\n\n" +
                        "data: {\"jsonrpc\":\"2.0\",\"id\":$id,\"result\":$result}\n\n")
                }
                else -> HttpClientResponse(400, HttpHeaders.of(), "")
            }
        }

    @Test
    fun negotiatesSessionPagesToolsAndCallsThem() = runTest {
        val http = server()
        val mcp = McpClient(http, "https://mcp.example/mcp", mapOf("Authorization" to "Bearer t"))
        val tools = mcp.tools(prefix = "calc_")
        assertEquals(listOf("calc_add", "calc_fail"), tools.map { it.name })
        assertEquals(neton.ai.AiToolKind.Dynamic, tools[0].kind)
        assertEquals("2025-06-18", mcp.protocolVersion)
        val ctx = neton.ai.AiToolCallContext(neton.ai.AiToolCall("c", "add", "{}"), 1, null, emptyMap(), emptyList())
        assertEquals(neton.ai.AiToolOutput.Text("3"), tools[0].execute!!.execute(Json.parseToJsonElement("""{"a":1,"b":2}"""), ctx))
        assertEquals(neton.ai.AiToolOutput.ErrorText("bad input"), tools[1].execute!!.execute(Json.parseToJsonElement("{}"), ctx))
        // 初始化之后的请求带会话 ID 与协商后的版本
        val later = http.recorded.last()
        assertEquals("S1", later.headers.get("Mcp-Session-Id"))
        assertEquals("2025-06-18", later.headers.get("MCP-Protocol-Version"))
        assertNull(http.recorded.first().headers.get("Mcp-Session-Id"))
        // 先探测 modern 协议，服务端不认识就回退握手
        assertEquals("server/discover", Json.parseToJsonElement(http.recorded.first().bodyText()!!).jsonObject["method"]!!.jsonPrimitive.content)
        assertEquals(McpProtocolEra.Legacy, mcp.era)
    }

    private val toolSchema = """{"type":"object","properties":{"region":{"type":"string","x-mcp-header":"Region"},
        "dry":{"type":"boolean","x-mcp-header":"Dry-Run"},"q":{"type":"string"}}}"""

    private fun modernServer(discover: (String?) -> HttpClientResponse = { id ->
        HttpClientResponse(200, HttpHeaders.of("Content-Type" to "application/json"),
            """{"jsonrpc":"2.0","id":$id,"result":{"resultType":"complete","supportedVersions":["2026-07-28","2025-11-25"],
               "capabilities":{"tools":{}},"instructions":"be nice","_meta":{"io.modelcontextprotocol/serverInfo":{"name":"m","version":"2"}}}}""")
    }): ScriptedHttpClient = ScriptedHttpClient()
        .on(HttpClientMethod.Post, "https://mcp.example/mcp") { req ->
            val msg = Json.parseToJsonElement(req.bodyText()!!).jsonObject
            val id = msg["id"]?.jsonPrimitive?.content
            fun ok(result: String) = HttpClientResponse(200, HttpHeaders.of("Content-Type" to "application/json"), """{"jsonrpc":"2.0","id":$id,"result":$result}""")
            when (msg["method"]!!.jsonPrimitive.content) {
                "server/discover" -> discover(id)
                "tools/list" -> ok("""{"resultType":"complete","tools":[{"name":"search","inputSchema":$toolSchema},
                    {"name":"broken","inputSchema":{"type":"object","properties":{"o":{"type":"object","x-mcp-header":"Obj"}}}}]}""")
                "tools/call" -> if (msg["params"]!!.jsonObject["name"]!!.jsonPrimitive.content == "search") ok("""{"resultType":"complete","content":[{"type":"text","text":"hit"}]}""")
                    else ok("""{"resultType":"input_required","inputRequests":{}}""")
                else -> HttpClientResponse(400, HttpHeaders.of(), "")
            }
        }

    @Test
    fun modernProtocolIsStatelessAndCarriesMetaAndHeaders() = runTest {
        val http = modernServer()
        val mcp = McpClient(http, "https://mcp.example/mcp")
        val tools = mcp.tools()
        assertEquals(McpProtocolEra.Modern, mcp.era)
        assertEquals("2026-07-28", mcp.protocolVersion)
        assertEquals(McpServerInfo("m", "2"), mcp.serverInfo)
        assertEquals("be nice", mcp.instructions)
        // 标注不合法的工具被忽略
        assertEquals(listOf("search"), tools.map { it.name })
        assertTrue(mcp.ignoredTools.getValue("broken").contains("boolean, integer, or string"))

        mcp.callTool("search", Json.parseToJsonElement("""{"region":"東京","dry":true,"q":"x"}""").jsonObject)
        val call = http.recorded.last()
        assertEquals("2026-07-28", call.headers.get("MCP-Protocol-Version"))
        assertEquals("tools/call", call.headers.get("Mcp-Method"))
        assertEquals("search", call.headers.get("Mcp-Name"))
        assertEquals("=?base64?5p2x5Lqs?=", call.headers.get("Mcp-Param-Region"))
        assertEquals("true", call.headers.get("Mcp-Param-Dry-Run"))
        assertTrue(http.recorded.none { it.headers.get("Mcp-Session-Id") != null })
        val meta = Json.parseToJsonElement(call.bodyText()!!).jsonObject["params"]!!.jsonObject["_meta"]!!.jsonObject
        assertEquals("2026-07-28", meta["io.modelcontextprotocol/protocolVersion"]!!.jsonPrimitive.content)
        assertEquals("neton-ai", meta["io.modelcontextprotocol/clientInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        // modern 协议不握手
        assertTrue(http.recorded.none { Json.parseToJsonElement(it.bodyText()!!).jsonObject["method"]!!.jsonPrimitive.content == "initialize" })

        assertFailsWith<McpException> { mcp.callTool("search", Json.parseToJsonElement("""{"dry":"yes"}""").jsonObject) }
        val e = assertFailsWith<McpException> { mcp.callTool("other", Json.parseToJsonElement("{}").jsonObject) }
        assertTrue(e.message!!.contains("multi round-trip"))
        // 没有声明的能力不发请求
        assertFailsWith<McpException> { mcp.listPrompts() }
    }

    @Test
    fun modernProtocolErrorsAreNotFallenBackFrom() = runTest {
        val http = modernServer { id ->
            HttpClientResponse(400, HttpHeaders.of("Content-Type" to "application/json"),
                """{"jsonrpc":"2.0","id":$id,"error":{"code":-32022,"message":"unsupported protocol version","data":{"supported":["2027-01-01"]}}}""")
        }
        val e = assertFailsWith<McpException> { McpClient(http, "https://mcp.example/mcp").listTools() }
        assertEquals(-32022, e.code)
        assertEquals(1, http.recorded.size)
    }

    @Test
    fun discoveryMustListTheModernVersionAndResultsNeedResultType() = runTest {
        val unsupported = modernServer { id -> HttpClientResponse(200, HttpHeaders.of("Content-Type" to "application/json"),
            """{"jsonrpc":"2.0","id":$id,"result":{"resultType":"complete","supportedVersions":["2027-01-01"],"capabilities":{}}}""") }
        assertFailsWith<McpException> { McpClient(unsupported, "https://mcp.example/mcp").connect() }
        val noType = modernServer { id -> HttpClientResponse(200, HttpHeaders.of("Content-Type" to "application/json"),
            """{"jsonrpc":"2.0","id":$id,"result":{"supportedVersions":["2026-07-28"],"capabilities":{}}}""") }
        // 与不认识 server/discover 一样回退到 legacy 握手（这台测试服务端不支持握手，于是失败）
        assertFailsWith<McpException> { McpClient(noType, "https://mcp.example/mcp").connect() }
        assertEquals(listOf("server/discover", "initialize"),
            noType.recorded.map { Json.parseToJsonElement(it.bodyText()!!).jsonObject["method"]!!.jsonPrimitive.content })
    }

    @Test
    fun headerRules() {
        assertEquals("plain-value", McpHeaders.encode("plain-value"))
        assertEquals("=?base64?IHg=?=", McpHeaders.encode(" x"))
        assertEquals("=?base64?PT9iYXNlNjQ/YWJjPz0=?=", McpHeaders.encode("=?base64?abc?="))
        fun schema(s: String) = Json.parseToJsonElement(s).jsonObject
        assertTrue(McpHeaders.bindings(schema("""{"type":"object","properties":{"a":{"type":"string","x-mcp-header":"X"},"b":{"type":"integer","x-mcp-header":"x"}}}""")) is McpHeaders.Bindings.Invalid)
        assertTrue(McpHeaders.bindings(schema("""{"type":"object","properties":{"a":{"type":"string","x-mcp-header":"bad name"}}}""")) is McpHeaders.Bindings.Invalid)
        assertTrue(McpHeaders.bindings(schema("""{"type":"object","anyOf":[{"properties":{"a":{"type":"string","x-mcp-header":"A"}}}]}""")) is McpHeaders.Bindings.Invalid)
        val nested = McpHeaders.bindings(schema("""{"type":"object","properties":{"o":{"type":"object","properties":{"n":{"type":"integer","x-mcp-header":"N"}}}}}"""))
        val b = (nested as McpHeaders.Bindings.Ok).bindings.single()
        assertEquals(listOf("o", "n"), b.path)
        assertEquals(mapOf("Mcp-Param-N" to "5"), McpHeaders.toolHeaders(listOf(b), schema("""{"o":{"n":5}}""")))
    }
}
