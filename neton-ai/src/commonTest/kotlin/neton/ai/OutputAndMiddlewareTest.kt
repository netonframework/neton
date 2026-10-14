package neton.ai

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import neton.ai.model.AiMiddlewares
import neton.ai.protocol.ir.CallOptions
import neton.ai.protocol.ir.ResponseFormat
import neton.ai.protocol.ir.TextBlock
import neton.ai.testing.AiScript
import neton.ai.testing.ScriptedAiProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OutputAndMiddlewareTest {
    @Serializable data class City(val name: String, val population: Int)

    private suspend fun output(reply: AiScript, out: AiOutput<*>): AiResult =
        ScriptedAiProvider(scripts = listOf(reply)).client().generate { user("x"); output = out }

    @Test
    fun objectOutputSendsStrictSchemaAndDecodes() = runTest {
        val p = ScriptedAiProvider(scripts = listOf(AiScript.text("""{"name":"Oslo","population":700000}""")))
        val r = p.client().generate { user("x"); output = AiOutput.obj<City>() }
        assertEquals(City("Oslo", 700000), r.requireOutput<City>())
        val format = p.requests.single().options.responseFormat as ResponseFormat.JsonSchema
        assertEquals(true, format.strict)
        assertEquals(JsonPrimitive(false), format.schema.jsonObject["additionalProperties"])
    }

    @Test
    fun failuresAreDistinguished() = runTest {
        assertIs<AiOutputResult.Refused>(output(AiScript.refusal("no"), AiOutput.obj<City>()).output)
        assertIs<AiOutputResult.Truncated>(output(AiScript.truncated("""{"name":"Os"""), AiOutput.obj<City>()).output)
        assertIs<AiOutputResult.Invalid>(output(AiScript.text("not json"), AiOutput.obj<City>()).output)
        assertIs<AiOutputResult.Invalid>(output(AiScript.text("""{"name":"Oslo"}"""), AiOutput.obj<City>()).output)
        assertIs<AiOutputResult.Success<*>>(output(AiScript.text("```json\n{\"name\":\"A\",\"population\":1}\n```"), AiOutput.obj<City>()).output)
        val e = assertFailsWith<AiOutputException> { output(AiScript.text("nope"), AiOutput.obj<City>()).requireOutput<City>() }
        assertIs<AiOutputResult.Invalid>(e.result)
    }

    @Test
    fun arrayChoiceAndJsonOutputs() = runTest {
        val arr = output(AiScript.text("""{"elements":[{"name":"A","population":1}]}"""), AiOutput.array<City>(maxItems = 3))
        assertEquals(listOf(City("A", 1)), arr.requireOutput<List<City>>())
        val tooMany = output(AiScript.text("""{"elements":[{"name":"A","population":1},{"name":"B","population":2}]}"""), AiOutput.array<City>(maxItems = 1))
        assertIs<AiOutputResult.Invalid>(tooMany.output)

        val choice = AiOutput.choice("positive", "negative", "neutral")
        assertEquals("negative", output(AiScript.text("""{"result":"negative"}"""), choice).requireOutput<String>())
        assertIs<AiOutputResult.Invalid>(output(AiScript.text("""{"result":"meh"}"""), choice).output)
        assertEquals(JsonPrimitive("negative"), choice.parsePartial("""{"result":"neg"""))
        assertEquals(null, AiOutput.choice("neg-a", "neg-b").parsePartial("""{"result":"neg"""))

        val json = output(AiScript.text("""[1,2]"""), AiOutput.Json)
        assertEquals(2, json.requireOutput<JsonArray>().size)
    }

    @Test
    fun outputIsNotGeneratedWhenTheLoopEndsOnToolCalls() = runTest {
        val r = output(AiScript.toolCall("1", "remote"), AiOutput.obj<City>())
        assertIs<AiOutputResult.NotGenerated>(r.output)
    }

    // ───────────────────────── 中间件 ─────────────────────────

    private fun clientWith(p: ScriptedAiProvider, vararg mw: neton.ai.model.AiModelMiddleware) = AiClient.create {
        provider(p); routing { defaultModel = "${p.id}:m" }; middleware(p.id, *mw)
    }

    @Test
    fun extractReasoningInGenerateAndStream() = runTest {
        val text = "<think>step one\nstep two</think>\nThe answer is 42."
        val g = clientWith(ScriptedAiProvider(scripts = listOf(AiScript.text(text))), AiMiddlewares.extractReasoning()).generate { user("x") }
        assertEquals("step one\nstep two", g.reasoningText)
        assertEquals("The answer is 42.", g.text)

        // 流式：标签被拆在任意位置也能识别
        val s = clientWith(ScriptedAiProvider(scripts = listOf(AiScript.Reply(listOf(TextBlock(text)), chunkSize = 3))), AiMiddlewares.extractReasoning())
            .stream { user("x") }.toList()
        val reasoning = s.filterIsInstance<AiStreamPart.ReasoningDelta>().joinToString("") { it.text }
        val answer = s.filterIsInstance<AiStreamPart.TextDelta>().joinToString("") { it.text }
        assertEquals("step one\nstep two", reasoning)
        assertEquals("The answer is 42.", answer)
        assertEquals("The answer is 42.", (s.last() as AiStreamPart.Finish).result.text)
    }

    @Test
    fun extractJsonStripsFences() = runTest {
        val fenced = "```json\n{\"name\":\"A\",\"population\":1}\n```"
        val c = clientWith(ScriptedAiProvider(scripts = listOf(AiScript.Reply(listOf(TextBlock(fenced)), chunkSize = 2))), AiMiddlewares.extractJson())
        val s = c.stream { user("x") }.toList()
        assertEquals("""{"name":"A","population":1}""", s.filterIsInstance<AiStreamPart.TextDelta>().joinToString("") { it.text })
        val g = clientWith(ScriptedAiProvider(scripts = listOf(AiScript.text(fenced))), AiMiddlewares.extractJson()).generate { user("x") }
        assertEquals("""{"name":"A","population":1}""", g.text)
    }

    @Test
    fun simulateStreamingUsesOneGenerateCall() = runTest {
        val p = ScriptedAiProvider(scripts = listOf(AiScript.text("whole answer")))
        val parts = clientWith(p, AiMiddlewares.simulateStreaming()).stream { user("x") }.toList()
        assertEquals("whole answer", parts.filterIsInstance<AiStreamPart.TextDelta>().single().text)
        assertEquals(false, p.requests.single().stream)
    }

    @Test
    fun defaultSettingsAndInstructions() = runTest {
        val p = ScriptedAiProvider(scripts = listOf(AiScript.text("ok")))
        clientWith(p, AiMiddlewares.defaultSettings(CallOptions(temperature = 0.2, maxOutputTokens = 100)), AiMiddlewares.defaultInstructions("be kind"))
            .generate { user("x"); maxOutputTokens = 50 }
        val req = p.requests.single()
        assertEquals(0.2, req.options.temperature)
        assertEquals(50, req.options.maxOutputTokens, "explicit values win")
        assertEquals("be kind", req.instructions.single().text)
    }

    // ───────────────────────── 工具函数 ─────────────────────────

    @Test
    fun pruneMessagesKeepsTheTailAndLinkedParts() {
        val call = AiToolCall("c1", "t", "{}")
        val old = AiToolCall("c0", "t", "{}")
        val msgs = listOf(
            AiMessage.user("q1"),
            AiMessage(AiRole.Assistant, listOf(AiContent.Reasoning("r0"), AiContent.ToolCall(old))),
            AiMessage.toolResult("c0", "t", AiToolOutput.Text("x")),
            AiMessage(AiRole.Assistant, listOf(AiContent.Reasoning("r1"), AiContent.ToolCall(call))),
            AiMessage.toolResult("c1", "t", AiToolOutput.Text("y")),
        )
        val pruned = pruneMessages(msgs, reasoning = AiPrune.All, toolCalls = AiPrune.BeforeLastMessage, keepLastMessages = 1)
        assertEquals(listOf(AiRole.User, AiRole.Assistant, AiRole.Tool), pruned.map { it.role })
        assertEquals(listOf("c1"), pruned[1].toolCalls.map { it.id })
        assertTrue(pruned.none { m -> m.content.any { it is AiContent.Reasoning } })
    }

    @Test
    fun cosine() {
        assertEquals(1.0, cosineSimilarity(floatArrayOf(1f, 2f), floatArrayOf(2f, 4f)), 1e-9)
        assertEquals(0.0, cosineSimilarity(floatArrayOf(0f, 0f), floatArrayOf(1f, 1f)))
        assertFailsWith<IllegalArgumentException> { cosineSimilarity(floatArrayOf(1f), floatArrayOf(1f, 2f)) }
    }

    @Test
    fun schemaValidator() {
        val schema = kotlinx.serialization.json.Json.parseToJsonElement("""
            {"type":"object","properties":{"n":{"type":"integer","minimum":0},"tags":{"type":"array","items":{"type":"string"},"maxItems":2},
             "mode":{"enum":["a","b"]}},"required":["n"],"additionalProperties":false}""").jsonObject
        fun v(s: String) = JsonSchemaValidator.validate(schema, kotlinx.serialization.json.Json.parseToJsonElement(s))
        assertEquals(null, v("""{"n":1,"tags":["x"],"mode":"a"}"""))
        assertEquals("$.n is required", v("""{}"""))
        assertEquals("$.n must be >= 0.0", v("""{"n":-1}"""))
        assertEquals("$.extra is not allowed", v("""{"n":1,"extra":true}"""))
        assertEquals("$.tags allows at most 2 items", v("""{"n":1,"tags":["a","b","c"]}"""))
        assertTrue(v("""{"n":1,"mode":"c"}""")!!.startsWith("$.mode must be one of"))
        assertEquals("$.n must be integer", v("""{"n":"1"}"""))
        assertEquals(listOf("A"), kotlinx.serialization.json.Json.parseToJsonElement("""{"x":["A"]}""").jsonObject["x"]!!.jsonArray.map { it.jsonPrimitive.content })
    }
}
