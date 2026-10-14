package neton.ai

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import neton.ai.protocol.ir.FunctionTool
import neton.ai.testing.AiScript
import neton.ai.testing.ScriptedAiProvider
import kotlin.test.Test
import kotlin.test.assertEquals

class AgentTest {
    @Test
    fun agentKeepsSettingsAndPrepareCallAdjustsThem() = runTest {
        val p = ScriptedAiProvider(scripts = listOf(AiScript.toolCall("1", "lookup"), AiScript.text("found"), AiScript.text("second")))
        val agent = p.client().agent(prepareCall = { r -> r.copy(instructions = r.instructions + " (vip)") }) {
            instructions = "You are a support agent."
            tool(AiTools.function("lookup", "look up an order") { _, _ -> "order 42" })
        }
        val first = agent.generate("where is my order?")
        assertEquals("found", first.text)
        assertEquals("You are a support agent. (vip)", p.requests[0].instructions.single().text)
        assertEquals(listOf("lookup"), p.requests[0].tools.map { (it as FunctionTool).name })

        // 无状态：调用方把历史接上再调用
        val history = listOf(AiMessage.user("where is my order?")) + first.responseMessages + AiMessage.user("thanks")
        val parts = agent.stream(history).toList()
        assertEquals("second", (parts.last() as AiStreamPart.Finish).result.text)
        assertEquals(5, p.requests.last().messages.size)
    }
}
