package neton.ai.builder

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import neton.ai.AiContent
import neton.ai.AiModelId
import neton.ai.AiRole
import neton.ai.ToolChoice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GenerateTextRequestBuilderTest {

    @Test fun systemUserAssistantHelpersCreateExpectedMessages() {
        val req = GenerateTextRequestBuilder().apply {
            system("You are helpful.")
            user("Hello")
            assistant("Hi there!")
        }.toRequest()
        assertEquals(3, req.messages.size)
        assertEquals(AiRole.System, req.messages[0].role)
        assertEquals(AiContent.Text("You are helpful."), req.messages[0].content.single())
        assertEquals(AiRole.User, req.messages[1].role)
        assertEquals(AiRole.Assistant, req.messages[2].role)
    }

    @Test fun toolResultHelperFillsToolCallId() {
        val req = GenerateTextRequestBuilder().apply {
            user("call tool")
            toolResult("call_42", """{"ok":true}""")
        }.toRequest()
        val toolMsg = req.messages.last()
        assertEquals(AiRole.Tool, toolMsg.role)
        assertEquals("call_42", toolMsg.toolCallId)
        assertEquals("""{"ok":true}""", (toolMsg.content.single() as AiContent.Text).text)
    }

    @Test fun modelStringParsedToAiModelId() {
        val req = GenerateTextRequestBuilder().apply {
            model = "openai:gpt-4o-mini"
            user("hi")
        }.toRequest()
        assertEquals(AiModelId("openai", "gpt-4o-mini"), req.model)
    }

    @Test fun nullModelStringYieldsNullModel() {
        val req = GenerateTextRequestBuilder().apply {
            modelPolicy = "strong"
            user("hi")
        }.toRequest()
        assertNull(req.model)
        assertEquals("strong", req.modelPolicy)
    }

    @Test fun toolBlockRegistersToolDefinition() {
        val req = GenerateTextRequestBuilder().apply {
            user("compute")
            tool("get_balance") {
                description = "Get user balance"
                inputSchemaJson = """{"type":"object"}"""
                execute { _ -> """{"balance":100}""" }
            }
        }.toRequest()
        assertEquals(1, req.tools.size)
        val def = req.tools.single()
        assertEquals("get_balance", def.name)
        assertEquals("Get user balance", def.description)
        assertEquals("""{"type":"object"}""", def.inputSchemaJson)
        assertTrue(def.executor != null, "executor should be set")
    }

    @Test fun typedToolExecutorDecodesAndEncodesViaJson() = runTest {
        val req = GenerateTextRequestBuilder().apply {
            user("compute")
            tool("get_balance") {
                description = "Get balance"
                inputSchemaJson = """{"type":"object","properties":{"userId":{"type":"integer"}}}"""
                execute<BalanceIn, BalanceOut> { input -> BalanceOut(balance = input.userId * 10) }
            }
        }.toRequest()
        val def = req.tools.single()
        val result = def.executor!!.execute("""{"userId":7}""")
        val parsed = Json.decodeFromString<BalanceOut>(result)
        assertEquals(70L, parsed.balance)
    }

    @Test fun toolChoiceDefaultsToAuto() {
        val req = GenerateTextRequestBuilder().apply { user("hi") }.toRequest()
        assertTrue(req.toolChoice is ToolChoice.Auto)
    }

    @Test fun defaultMaxToolRoundsIs3() {
        val req = GenerateTextRequestBuilder().apply { user("hi") }.toRequest()
        assertEquals(3, req.maxToolRounds)
    }

    @Serializable data class BalanceIn(val userId: Long)
    @Serializable data class BalanceOut(val balance: Long)
}
