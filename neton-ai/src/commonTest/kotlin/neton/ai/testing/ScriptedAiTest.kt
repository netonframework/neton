package neton.ai.testing

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import neton.ai.AiError
import neton.ai.AiMessage
import neton.ai.AiStreamEvent
import neton.ai.AiToolCall
import neton.ai.AiToolDefinition
import neton.ai.GenerateTextRequest
import neton.ai.StreamTextRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ScriptedAiTest {
    @Test
    fun multiStepToolLoopWithoutHttp() = runTest {
        val p = ScriptedAiProvider(scripts = listOf(AiScript.toolCalls(AiToolCall("1", "now", "{}")), AiScript.text("it is noon")))
        val r = p.client().generateText(GenerateTextRequest(messages = listOf(AiMessage.user("time?")),
            tools = listOf(AiToolDefinition("now", "", "{}") { "12:00" })))
        assertEquals("it is noon", r.text)
        assertEquals(2, r.steps.size)
        assertEquals(2, p.requests.size)
        assertEquals("12:00", p.requests[1].messages.last().text)
    }

    @Test
    fun streamingTimingAndCancellation() = runTest {
        val p = ScriptedAiProvider(scripts = listOf(AiScript.text("abcdefghijkl", delayMillis = 100)))
        // 只取前几个事件后取消：不会得到 Completed
        val first = p.client().streamText(StreamTextRequest(messages = listOf(AiMessage.user("x")))).take(2).toList()
        assertIs<AiStreamEvent.Started>(first[0])
        assertIs<AiStreamEvent.TextDelta>(first[1])
        val all = ScriptedAiProvider(scripts = listOf(AiScript.text("abcdefgh"))).client()
            .streamText(StreamTextRequest(messages = listOf(AiMessage.user("x")))).toList()
        assertEquals(listOf("abcd", "efgh"), all.filterIsInstance<AiStreamEvent.TextDelta>().map { it.text })
        assertTrue(all.last() is AiStreamEvent.Completed)
    }

    @Test
    fun scriptedFailureMidStream() = runTest {
        val p = ScriptedAiProvider(scripts = listOf(AiScript.Fail(AiError.ServerError(500, "boom"), afterTextDeltas = "par")))
        val events = p.client().streamText(StreamTextRequest(messages = listOf(AiMessage.user("x")))).toList()
        assertIs<AiStreamEvent.Failed>(events.last())
    }
}
