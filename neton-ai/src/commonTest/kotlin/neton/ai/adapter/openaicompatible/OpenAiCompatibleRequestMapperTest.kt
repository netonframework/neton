// neton-ai/src/commonTest/kotlin/neton/ai/adapter/openaicompatible/OpenAiCompatibleRequestMapperTest.kt
package neton.ai.adapter.openaicompatible

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import neton.ai.AiContent
import neton.ai.AiMessage
import neton.ai.AiRole
import neton.ai.AiToolCall
import neton.ai.AiToolDefinition
import neton.ai.ToolChoice
import neton.ai.adapter.openaicompatible.dto.OpenAiChatRequest
import neton.ai.provider.ProviderCallRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenAiCompatibleRequestMapperTest {

    private val mapper = OpenAiCompatibleRequestMapper()
    private val json = Json { encodeDefaults = false; explicitNulls = false }

    private fun req(messages: List<AiMessage> = emptyList(),
                    tools: List<AiToolDefinition> = emptyList(),
                    toolChoice: ToolChoice = ToolChoice.Auto,
                    temperature: Double? = null) =
        ProviderCallRequest(messages, tools, toolChoice, temperature, null, null, emptyList(), emptyMap())

    @Test fun systemUserAssistantMessagesMap() {
        val out: OpenAiChatRequest = mapper.toWire("gpt-4o-mini", req(messages = listOf(
            AiMessage(AiRole.System, listOf(AiContent.Text("sys"))),
            AiMessage(AiRole.User, listOf(AiContent.Text("hi"))),
            AiMessage(AiRole.Assistant, listOf(AiContent.Text("hello"))),
        )))
        assertEquals("gpt-4o-mini", out.model)
        assertEquals(3, out.messages.size)
        assertEquals("system", out.messages[0].role)
        assertEquals("sys", out.messages[0].content)
        assertEquals("user", out.messages[1].role)
        assertEquals("assistant", out.messages[2].role)
    }

    @Test fun multipleTextContentConcatenatedWithNewline() {
        val out = mapper.toWire("m", req(messages = listOf(
            AiMessage(AiRole.User, listOf(AiContent.Text("line1"), AiContent.Text("line2"))),
        )))
        assertEquals("line1\nline2", out.messages.single().content)
    }

    @Test fun assistantToolCallsMapToToolCallsField() {
        val out = mapper.toWire("m", req(messages = listOf(
            AiMessage(
                role = AiRole.Assistant,
                content = emptyList(),
                toolCalls = listOf(AiToolCall("c1", "get_balance", """{"userId":7}""")),
            ),
        )))
        val msg = out.messages.single()
        assertEquals("assistant", msg.role)
        assertNull(msg.content)
        assertEquals(1, msg.toolCalls?.size)
        assertEquals("c1", msg.toolCalls!![0].id)
        assertEquals("get_balance", msg.toolCalls[0].function.name)
        assertEquals("""{"userId":7}""", msg.toolCalls[0].function.arguments)
    }

    @Test fun toolRoleMessageMapsWithToolCallId() {
        val out = mapper.toWire("m", req(messages = listOf(
            AiMessage(
                role = AiRole.Tool,
                content = listOf(AiContent.Text("""{"balance":42}""")),
                toolCallId = "c1",
            ),
        )))
        val msg = out.messages.single()
        assertEquals("tool", msg.role)
        assertEquals("c1", msg.toolCallId)
        assertEquals("""{"balance":42}""", msg.content)
    }

    @Test fun toolDefinitionsMapToToolsField() {
        val out = mapper.toWire("m", req(tools = listOf(
            AiToolDefinition(
                name = "get_balance",
                description = "Get user balance",
                inputSchemaJson = """{"type":"object","properties":{"userId":{"type":"integer"}}}""",
            ),
        )))
        assertEquals(1, out.tools?.size)
        val tool = out.tools!!.single()
        assertEquals("function", tool.type)
        assertEquals("get_balance", tool.function.name)
        assertEquals("Get user balance", tool.function.description)
        // parameters is raw JSON tree
        val params = tool.function.parameters as JsonObject
        assertEquals(JsonPrimitive("object"), params["type"])
    }

    @Test fun toolChoiceAutoMapsToStringAuto() {
        val out = mapper.toWire("m", req(toolChoice = ToolChoice.Auto))
        assertEquals(JsonPrimitive("auto"), out.toolChoice)
    }

    @Test fun toolChoiceNoneMapsToStringNone() {
        val out = mapper.toWire("m", req(toolChoice = ToolChoice.None))
        assertEquals(JsonPrimitive("none"), out.toolChoice)
    }

    @Test fun toolChoiceRequiredMapsToStringRequired() {
        val out = mapper.toWire("m", req(toolChoice = ToolChoice.Required))
        assertEquals(JsonPrimitive("required"), out.toolChoice)
    }

    @Test fun toolChoiceNamedMapsToFunctionObject() {
        val out = mapper.toWire("m", req(toolChoice = ToolChoice.Named("my_tool")))
        val tc = out.toolChoice as JsonObject
        assertEquals(JsonPrimitive("function"), tc["type"])
        assertEquals(JsonPrimitive("my_tool"), (tc["function"] as JsonObject)["name"])
    }

    @Test fun nonStreamRequestHasStreamFalseOrUnset() {
        val out = mapper.toWire("m", req())
        assertTrue(out.stream == null || out.stream == false)
    }

    @Test fun roundTripsThroughJsonWithoutLoss() {
        // Encode → decode → re-encode produces same JSON (no field-level drift)
        val original = mapper.toWire("m", req(messages = listOf(
            AiMessage(AiRole.User, listOf(AiContent.Text("hi"))),
        )))
        val encoded = json.encodeToString(OpenAiChatRequest.serializer(), original)
        val decoded = json.decodeFromString(OpenAiChatRequest.serializer(), encoded)
        assertEquals(original, decoded)
    }
}
