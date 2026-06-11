package neton.ai.adapter.anthropic

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import neton.ai.AiContent
import neton.ai.AiMessage
import neton.ai.AiRole
import neton.ai.AiToolCall
import neton.ai.AiToolDefinition
import neton.ai.ToolChoice
import neton.ai.adapter.anthropic.dto.AnthropicContentBlock
import neton.ai.provider.ProviderCallRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnthropicRequestMapperTest {

    private val mapper = AnthropicRequestMapper()

    private fun req(messages: List<AiMessage> = emptyList(),
                    tools: List<AiToolDefinition> = emptyList(),
                    toolChoice: ToolChoice = ToolChoice.Auto,
                    maxTokens: Int? = null) =
        ProviderCallRequest(messages, tools, toolChoice, null, maxTokens, null, emptyList(), emptyMap())

    @Test fun systemMessagesMergedIntoSystemField() {
        val out = mapper.toWire("claude", req(
            messages = listOf(
                AiMessage(AiRole.System, listOf(AiContent.Text("sys-a"))),
                AiMessage(AiRole.System, listOf(AiContent.Text("sys-b"))),
                AiMessage(AiRole.User, listOf(AiContent.Text("hi"))),
            ),
            maxTokens = 1024,
        ))
        assertEquals("sys-a\n\nsys-b", out.system)
        assertEquals(1, out.messages.size)
        assertEquals("user", out.messages[0].role)
    }

    @Test fun noSystemMessagesYieldsNullSystem() {
        val out = mapper.toWire("claude", req(
            messages = listOf(AiMessage(AiRole.User, listOf(AiContent.Text("hi")))),
            maxTokens = 1024,
        ))
        assertNull(out.system)
    }

    @Test fun maxTokensRequiredDefaultsTo4096IfNotProvided() {
        // Anthropic requires max_tokens; mapper provides a default to avoid forcing every caller to set it
        val out = mapper.toWire("claude", req(
            messages = listOf(AiMessage(AiRole.User, listOf(AiContent.Text("hi")))),
            maxTokens = null,
        ))
        assertEquals(4096, out.maxTokens)
    }

    @Test fun maxTokensExplicitlyProvidedIsUsed() {
        val out = mapper.toWire("claude", req(
            messages = listOf(AiMessage(AiRole.User, listOf(AiContent.Text("hi")))),
            maxTokens = 200,
        ))
        assertEquals(200, out.maxTokens)
    }

    @Test fun userMessageTextMapsToTextContentBlock() {
        val out = mapper.toWire("claude", req(
            messages = listOf(AiMessage(AiRole.User, listOf(AiContent.Text("hello")))),
            maxTokens = 1024,
        ))
        val block = out.messages.single().content.single() as AnthropicContentBlock.Text
        assertEquals("hello", block.text)
    }

    @Test fun assistantToolCallsMapToToolUseContentBlocks() {
        val out = mapper.toWire("claude", req(
            messages = listOf(AiMessage(
                role = AiRole.Assistant,
                content = listOf(AiContent.Text("Calling tool.")),
                toolCalls = listOf(AiToolCall("c1", "get_balance", """{"userId":7}""")),
            )),
            maxTokens = 1024,
        ))
        val msg = out.messages.single()
        assertEquals("assistant", msg.role)
        assertEquals(2, msg.content.size, "text + tool_use")
        val text = msg.content[0] as AnthropicContentBlock.Text
        assertEquals("Calling tool.", text.text)
        val toolUse = msg.content[1] as AnthropicContentBlock.ToolUse
        assertEquals("c1", toolUse.id)
        assertEquals("get_balance", toolUse.name)
        val input = toolUse.input as JsonObject
        assertEquals(JsonPrimitive(7L), input["userId"])
    }

    @Test fun toolRoleMessageMapsToUserToolResultBlock() {
        val out = mapper.toWire("claude", req(
            messages = listOf(AiMessage(
                role = AiRole.Tool,
                content = listOf(AiContent.Text("""{"balance":42}""")),
                toolCallId = "c1",
            )),
            maxTokens = 1024,
        ))
        val msg = out.messages.single()
        assertEquals("user", msg.role, "Anthropic uses user role for tool_result")
        val block = msg.content.single() as AnthropicContentBlock.ToolResult
        assertEquals("c1", block.toolUseId)
        assertEquals("""{"balance":42}""", block.content)
    }

    @Test fun erroredToolRoleMessageMapsToAnthropicIsError() {
        val out = mapper.toWire("claude", req(
            messages = listOf(AiMessage(
                role = AiRole.Tool,
                content = listOf(AiContent.Text("boom")),
                toolCallId = "c1",
                toolResultIsError = true,
            )),
            maxTokens = 1024,
        ))
        val block = out.messages.single().content.single() as AnthropicContentBlock.ToolResult
        assertEquals(true, block.isError)
    }

    @Test fun toolDefinitionsMapToToolsField() {
        val out = mapper.toWire("claude", req(
            tools = listOf(AiToolDefinition(
                name = "get_balance",
                description = "Get balance",
                inputSchemaJson = """{"type":"object","properties":{"userId":{"type":"integer"}}}""",
            )),
            messages = listOf(AiMessage(AiRole.User, listOf(AiContent.Text("hi")))),
            maxTokens = 1024,
        ))
        assertEquals(1, out.tools?.size)
        val tool = out.tools!!.single()
        assertEquals("get_balance", tool.name)
        assertEquals("Get balance", tool.description)
        // input_schema is wire field name (not "parameters")
    }

    @Test fun toolChoiceAutoMapsToAutoType() {
        val out = mapper.toWire("c", req(messages = listOf(AiMessage(AiRole.User, listOf(AiContent.Text("x")))), maxTokens = 100, toolChoice = ToolChoice.Auto))
        // tool_choice is omitted when Auto with no tools; or {type:"auto"} when tools present
        // For "no tools + Auto": null toolChoice is acceptable
        assertTrue(out.toolChoice == null || out.toolChoice!!.type == "auto")
    }

    @Test fun toolChoiceRequiredMapsToAnyType() {
        val out = mapper.toWire("c", req(
            messages = listOf(AiMessage(AiRole.User, listOf(AiContent.Text("x")))),
            maxTokens = 100,
            tools = listOf(AiToolDefinition("t", "", "{}")),
            toolChoice = ToolChoice.Required,
        ))
        assertEquals("any", out.toolChoice?.type)
    }

    @Test fun toolChoiceNamedMapsToToolTypeWithName() {
        val out = mapper.toWire("c", req(
            messages = listOf(AiMessage(AiRole.User, listOf(AiContent.Text("x")))),
            maxTokens = 100,
            tools = listOf(AiToolDefinition("my_tool", "", "{}")),
            toolChoice = ToolChoice.Named("my_tool"),
        ))
        assertEquals("tool", out.toolChoice?.type)
        assertEquals("my_tool", out.toolChoice?.name)
    }

    @Test fun toolChoiceNoneOmitsToolsFromRequest() {
        val out = mapper.toWire("c", req(
            messages = listOf(AiMessage(AiRole.User, listOf(AiContent.Text("x")))),
            maxTokens = 100,
            tools = listOf(AiToolDefinition("t", "", "{}")),
            toolChoice = ToolChoice.None,
        ))
        assertTrue(out.tools == null || out.tools!!.isEmpty(), "ToolChoice.None must drop tools from request")
    }
}
