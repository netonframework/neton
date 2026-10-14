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
import kotlin.test.assertNull

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
    }
}
