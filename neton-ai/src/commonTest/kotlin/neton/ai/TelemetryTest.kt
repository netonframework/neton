package neton.ai

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import neton.ai.provider.AiProvider
import neton.ai.provider.EmbedOptions
import neton.ai.provider.AiEmbeddingModel
import neton.ai.provider.EmbedResult
import neton.ai.telemetry.AiOperation
import neton.ai.telemetry.AiSpan
import neton.ai.telemetry.AiSpanKind
import neton.ai.telemetry.AiTelemetry
import neton.ai.telemetry.AiTelemetryEvent
import neton.ai.telemetry.AiTelemetrySettings
import neton.ai.telemetry.GenAiSpans
import neton.ai.testing.AiScript
import neton.ai.testing.ScriptedAiProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TelemetryTest {
    private class Recorder : AiTelemetry {
        val events = mutableListOf<AiTelemetryEvent>()
        override suspend fun onEvent(event: AiTelemetryEvent) { events += event }
        fun names() = events.map { it::class.simpleName!! }
    }

    private fun client(p: AiProvider, vararg t: AiTelemetry, settings: AiTelemetrySettings = AiTelemetrySettings()) = AiClient.create {
        provider(p); routing { defaultModel = "${p.id}:m" }; telemetry(*t); telemetrySettings = settings
    }

    private val weather = AiTools.function("weather", "weather") { _, _ -> "sunny" }

    @Test
    fun eventsFollowTheLoopAndCarryNoContentByDefault() = runTest {
        val rec = Recorder()
        val p = ScriptedAiProvider(scripts = listOf(AiScript.toolCall("c1", "weather", """{"city":"Oslo"}"""), AiScript.text("sunny in Oslo")))
        client(p, rec).generate { user("weather?"); tool(weather); functionId = "support"; metadata = mapOf("tenant" to "t1") }
        assertEquals(listOf(
            "OperationStart", "StepStart", "ModelCallStart", "ModelCallEnd", "ToolStart", "ToolEnd", "StepEnd",
            "StepStart", "ModelCallStart", "ModelCallEnd", "StepEnd", "OperationEnd",
        ), rec.names())
        val ctx = rec.events.first().context
        assertEquals(AiOperation.Generate, ctx.operation)
        assertEquals("support", ctx.functionId)
        assertEquals("t1", ctx.metadata["tenant"])
        assertTrue(rec.events.all { it.context == ctx })
        // 默认不带内容
        assertNull((rec.events[0] as AiTelemetryEvent.OperationStart).input)
        assertNull(rec.events.filterIsInstance<AiTelemetryEvent.ToolStart>().single().input)
        assertNull(rec.events.filterIsInstance<AiTelemetryEvent.ModelCallEnd>().first().output)
        assertEquals(2, (rec.events.last() as AiTelemetryEvent.OperationEnd).steps)
    }

    @Test
    fun contentIsRecordedOnlyWhenEnabled() = runTest {
        val rec = Recorder()
        val p = ScriptedAiProvider(scripts = listOf(AiScript.toolCall("c1", "weather", """{"city":"Oslo"}"""), AiScript.text("done")))
        client(p, rec, settings = AiTelemetrySettings(recordInputs = true, recordOutputs = true)).generate { instructions = "be brief"; user("weather?"); tool(weather) }
        val start = rec.events[0] as AiTelemetryEvent.OperationStart
        assertEquals(listOf(AiRole.System, AiRole.User), start.input!!.map { it.role })
        assertEquals("""{"city":"Oslo"}""", rec.events.filterIsInstance<AiTelemetryEvent.ToolStart>().single().input.toString())
        assertEquals("sunny", rec.events.filterIsInstance<AiTelemetryEvent.ToolEnd>().single().output!!.asText())
        assertNotNull(rec.events.filterIsInstance<AiTelemetryEvent.ModelCallEnd>().last().output)
    }

    @Test
    fun genAiSpansFollowTheSemanticConventions() = runTest {
        val spans = mutableListOf<AiSpan>()
        var t = 0L
        val p = ScriptedAiProvider(scripts = listOf(AiScript.toolCall("c1", "weather"), AiScript.text("sunny")))
        client(p, GenAiSpans({ spans += it }, clockNanos = { t += 1000; t })).generate {
            user("weather?"); tool(weather); functionId = "support"
            metadata = mapOf("traceparent" to "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01")
        }
        val root = spans.single { it.name == "invoke_agent support" }
        assertEquals("0af7651916cd43dd8448eb211c80319c", root.traceId)
        assertEquals("b7ad6b7169203331", root.parentSpanId)
        assertEquals("invoke_agent", root.attributes["gen_ai.operation.name"])
        assertEquals("support", root.attributes["gen_ai.agent.name"])
        assertEquals("scripted", root.attributes["gen_ai.provider.name"])
        assertEquals(false, root.attributes["gen_ai.request.stream"])

        val chats = spans.filter { it.name == "chat m" }
        assertEquals(2, chats.size)
        assertTrue(chats.all { it.parentSpanId == root.spanId && it.kind == AiSpanKind.Client && it.traceId == root.traceId })
        assertEquals(listOf("tool_calls"), chats[0].attributes["gen_ai.response.finish_reasons"])
        val tool = spans.single { it.name == "execute_tool weather" }
        assertEquals(root.spanId, tool.parentSpanId)
        assertEquals("c1", tool.attributes["gen_ai.tool.call.id"])
        // 子 span 先结束、根 span 最后结束
        assertEquals(root, spans.last())
        assertTrue(spans.all { it.startEpochNanos < it.endEpochNanos })
        // 默认没有内容属性
        assertTrue(spans.none { s -> s.attributes.keys.any { it in setOf("gen_ai.input.messages", "gen_ai.output.messages", "gen_ai.tool.call.arguments", "gen_ai.tool.call.result") } })
    }

    @Test
    fun retriesAndFailuresAreObservable() = runTest {
        val rec = Recorder()
        val spans = mutableListOf<AiSpan>()
        val p = ScriptedAiProvider(scripts = listOf(
            AiScript.error(AiError.ServerError(503, "busy")),
            AiScript.error(AiError.ServerError(503, "busy")),
            AiScript.error(AiError.ServerError(503, "busy")),
        ))
        assertFailsWith<AiException> { client(p, rec, GenAiSpans({ spans += it })).generate { user("x") } }
        assertEquals(listOf(1, 2), rec.events.filterIsInstance<AiTelemetryEvent.ModelCallRetry>().map { it.attempt })
        assertEquals(listOf(2_000L, 4_000L), rec.events.filterIsInstance<AiTelemetryEvent.ModelCallRetry>().map { it.delayMs })
        assertEquals("ServerError", (rec.events.last() as AiTelemetryEvent.OperationError).error.kind)
        val chat = spans.single { it.name == "chat m" }
        assertEquals("ServerError", chat.errorType)
        assertEquals(2, chat.events.count { it.name == "neton.ai.retry" })
        assertEquals("ServerError", spans.last().errorType)
    }

    @Test
    fun cancellationClosesTheOperation() = runTest {
        val rec = Recorder()
        val spans = mutableListOf<AiSpan>()
        val p = ScriptedAiProvider(scripts = listOf(AiScript.text("abcdefghijkl", delayMillis = 100)))
        client(p, rec, GenAiSpans({ spans += it })).stream { user("x") }.take(3).toList()
        assertEquals(AiOperation.Stream, rec.events.first().context.operation)
        assertTrue(rec.events.last() is AiTelemetryEvent.OperationError)
        // 开着的模型调用 span 与根 span 都被关闭
        assertEquals(setOf("chat m", "invoke_agent"), spans.map { it.name }.toSet())
        assertEquals("cancelled", spans.single { it.name == "chat m" }.errorType)
    }

    @Test
    fun brokenIntegrationsDoNotBreakCalls() = runTest {
        val rec = Recorder()
        val p = ScriptedAiProvider(scripts = listOf(AiScript.text("ok")))
        val r = client(p, AiTelemetry { throw IllegalStateException("boom") }, rec).generate { user("x") }
        assertEquals("ok", r.text)
        assertEquals("OperationEnd", rec.names().last())
    }

    @Test
    fun embeddingsAreTraced() = runTest {
        val spans = mutableListOf<AiSpan>()
        val provider = object : AiProvider {
            override val id = "emb"
            override fun languageModel(modelId: String): neton.ai.model.AiLanguageModel? = null
            override fun embeddingModel(modelId: String) = object : AiEmbeddingModel {
                override val provider = "emb"
                override val modelId = modelId
                override suspend fun embed(values: List<String>, options: EmbedOptions) =
                    EmbedResult(values.map { floatArrayOf(1f) }, AiUsage(inputTokens = 3))
            }
        }
        client(provider, GenAiSpans({ spans += it })).embed(EmbeddingRequest(AiModelId("emb", "e1"), listOf("a", "b")))
        val s = spans.single()
        assertEquals("embeddings e1", s.name)
        assertEquals("embeddings", s.attributes["gen_ai.operation.name"])
        assertEquals(3L, s.attributes["gen_ai.usage.input_tokens"])
    }
}
