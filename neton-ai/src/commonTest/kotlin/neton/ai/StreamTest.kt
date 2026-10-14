package neton.ai

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import neton.ai.protocol.ir.TextBlock
import neton.ai.testing.AiScript
import neton.ai.testing.ScriptedAiProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StreamTest {

    @Test
    fun partsFollowTheDocumentedSequence() = runTest {
        val p = ScriptedAiProvider(scripts = listOf(AiScript.toolCall("1", "now", text = "checking"), AiScript.reasoning("think", "noon")))
        val parts = p.client().stream {
            user("time?")
            tool(AiTools.function("now", "now") { _, _ -> "12:00" })
        }.toList()
        val types = parts.map { it::class.simpleName!! }
        assertEquals(listOf(
            "StepStart", "TextStart", "TextDelta", "TextDelta", "TextEnd", "ToolInputStart", "ToolInputDelta", "ToolInputEnd", "ToolCall",
            "ToolResult", "StepFinish",
            "StepStart", "ReasoningStart", "ReasoningDelta", "ReasoningEnd", "TextStart", "TextDelta", "TextEnd", "StepFinish", "Finish",
        ), types)
        val result = (parts.last() as AiStreamPart.Finish).result
        assertEquals("noon", result.text)
        assertEquals("think", result.reasoningText)
        assertEquals("checking", parts.filterIsInstance<AiStreamPart.TextDelta>().take(2).joinToString("") { it.text })
    }

    @Test
    fun preliminaryToolResultsStreamBeforeTheFinalOne() = runTest {
        val p = ScriptedAiProvider(scripts = listOf(AiScript.toolCall("1", "job"), AiScript.text("done")))
        val job = AiTool("job", execute = AiToolExecutor { _, ctx ->
            ctx.preliminary(AiToolOutput.Text("25%"))
            ctx.preliminary(AiToolOutput.Text("75%"))
            AiToolOutput.Text("100%")
        })
        val results = p.client().stream { user("go"); tool(job) }.toList().filterIsInstance<AiStreamPart.ToolResult>().map { it.result }
        assertEquals(listOf(true, true, false), results.map { it.preliminary })
        assertEquals(listOf("25%", "75%", "100%"), results.map { it.output.asText() })
    }

    @Serializable data class City(val name: String, val population: Int)

    @Test
    fun partialOutputIsNeverTheResult() = runTest {
        val json = """{"elements":[{"name":"Oslo","population":700000},{"name":"Bergen","population":285000}]}"""
        val p = ScriptedAiProvider(scripts = listOf(AiScript.Reply(listOf(TextBlock(json)), chunkSize = 9)))
        val parts = p.client().stream { user("cities"); output = AiOutput.array<City>() }.toList()
        val partials = parts.filterIsInstance<AiStreamPart.PartialOutput>().map { it.value as JsonArray }
        assertTrue(partials.isNotEmpty())
        // 半成品只包含完整的元素，且逐步增长
        assertTrue(partials.all { arr -> arr.all { it.jsonObject.containsKey("population") } })
        assertEquals(listOf(0, 1, 2), partials.map { it.size }.distinct())
        val result = (parts.last() as AiStreamPart.Finish).result
        assertEquals(listOf(City("Oslo", 700000), City("Bergen", 285000)), result.requireOutput<List<City>>())
    }

    @Test
    fun errorsEndTheStreamAndRetriesHappenOnlyBeforeOutput() = runTest {
        val p = ScriptedAiProvider(scripts = listOf(AiScript.error(AiError.ServerError(503, "busy")), AiScript.text("ok")))
        val r = p.client().stream { user("go") }.result()
        assertEquals("ok", r.text)
        assertEquals(2, p.requests.size)

        // 已经输出过内容：不重试，以 Error 结束
        val p2 = ScriptedAiProvider(scripts = listOf(AiScript.Fail(AiError.ServerError(503, "busy"), afterText = "par"), AiScript.text("never")))
        val parts = p2.client().stream { user("go") }.toList()
        assertIs<AiStreamPart.Error>(parts.last())
        assertTrue(parts.any { it is AiStreamPart.TextDelta })
        assertEquals(1, p2.requests.size)
    }

    @Test
    fun cancellationStopsWithoutFinish() = runTest {
        val p = ScriptedAiProvider(scripts = listOf(AiScript.text("abcdefghijkl", delayMillis = 100)))
        val first = p.client().stream { user("x") }.take(3).toList()
        assertEquals(listOf("StepStart", "TextStart", "TextDelta"), first.map { it::class.simpleName })
    }

    @Test
    fun chunkTimeoutFailsTheStream() = runTest {
        val p = ScriptedAiProvider(scripts = listOf(AiScript.text("abcdefgh", delayMillis = 500)))
        val parts = p.client().stream { user("x"); maxRetries = 0; timeout = AiTimeout(chunkMs = 100) }.toList()
        val err = (parts.last() as AiStreamPart.Error).error
        assertIs<AiError.Timeout>(err)
    }

    @Test
    fun smoothReChunksByWord() = runTest {
        val p = ScriptedAiProvider(scripts = listOf(AiScript.Reply(listOf(TextBlock("hello brave new world")), chunkSize = 3)))
        val deltas = p.client().stream { user("x") }.smooth(delayMs = 0).toList().filterIsInstance<AiStreamPart.TextDelta>().map { it.text }
        assertEquals(listOf("hello ", "brave ", "new ", "world"), deltas)
    }

    @Test
    fun refusalIsItsOwnPart() = runTest {
        val p = ScriptedAiProvider(scripts = listOf(AiScript.refusal("can't help")))
        val parts = p.client().stream { user("x"); output = AiOutput.obj<City>() }.toList()
        assertEquals("can't help", parts.filterIsInstance<AiStreamPart.Refusal>().single().text)
        assertEquals(AiOutputResult.Refused("can't help"), (parts.last() as AiStreamPart.Finish).result.output)
    }
}
