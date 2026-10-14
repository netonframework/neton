package neton.ai

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import neton.ai.internal.DefaultModelRouter
import neton.ai.internal.DefaultProviderRegistry
import neton.ai.internal.runStreamingToolLoop
import neton.ai.internal.runToolLoop
import neton.ai.provider.AiProvider
import neton.ai.provider.AiStreamingTextModel
import neton.ai.provider.ProviderCallRequest
import neton.ai.provider.ProviderCallResponse
import neton.ai.routing.RoutingConfig
import neton.ai.usage.NoopAiUsageRecorder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentTest {

    /** 按轮次返回脚本化响应的模型；记录每次收到的请求。 */
    private class Scripted(override val providerId: String, override val modelName: String, val replies: List<ProviderCallResponse>) : AiStreamingTextModel {
        val requests = mutableListOf<ProviderCallRequest>()
        override suspend fun generate(request: ProviderCallRequest): ProviderCallResponse {
            requests += request
            return replies[(requests.size - 1).coerceAtMost(replies.lastIndex)]
        }
        override fun stream(request: ProviderCallRequest) = kotlinx.coroutines.flow.flow {
            val r = generate(request)
            r.toolCalls.forEach { emit(AiStreamEvent.ToolCallReady(it)) }
            if (r.text.isNotEmpty()) emit(AiStreamEvent.TextDelta(r.text))
            emit(AiStreamEvent.Completed(r.message, r.text, r.usage, r.finishReason))
        }
    }

    private fun provider(vararg models: Scripted) = object : AiProvider {
        override val id = models.first().providerId
        override fun textModel(modelName: String) = models.firstOrNull { it.modelName == modelName }
        override fun streamingTextModel(modelName: String) = models.firstOrNull { it.modelName == modelName }
        override fun embeddingModel(modelName: String) = null
    }

    private fun calls(vararg calls: AiToolCall) = ProviderCallResponse(AiMessage.assistant(toolCalls = calls.toList()), "", calls.toList(), null, AiFinishReason.ToolCalls)
    private fun text(t: String) = ProviderCallResponse(AiMessage.assistant(t), t, emptyList(), AiUsage(1, 1), AiFinishReason.Stop)

    private suspend fun run(p: AiProvider, req: GenerateTextRequest) = runToolLoop(
        req.copy(model = AiModelId(p.id, req.model?.modelName ?: "m")),
        DefaultProviderRegistry(mapOf(p.id to p)), DefaultModelRouter(RoutingConfig()), NoopAiUsageRecorder,
    )

    private fun tool(name: String, parallelSafe: Boolean = false, body: suspend (String) -> String = { "ok:$name" }) =
        AiToolDefinition(name, name, """{"type":"object"}""", parallelSafe = parallelSafe, executor = AiToolExecutor { body(it) })

    @Test
    fun stopWhenHasToolCallEndsTheLoop() = runTest {
        val m = Scripted("p", "m", listOf(calls(AiToolCall("1", "search", "{}")), calls(AiToolCall("2", "finish", "{}")), text("never")))
        val r = run(provider(m), GenerateTextRequest(messages = listOf(AiMessage.user("go")), tools = listOf(tool("search"), tool("finish")),
            maxToolRounds = 5, agent = AiAgentOptions(stopWhen = listOf(AiStopCondition.hasToolCall("finish")))))
        assertEquals(2, r.steps.size)
        assertEquals(2, m.requests.size)
        assertEquals(listOf("ok:search", "ok:finish"), r.toolResults.map { it.content })
        assertEquals(AiFinishReason.ToolCalls, r.finishReason)
    }

    @Test
    fun prepareStepSwapsModelAndTools() = runTest {
        val small = Scripted("p", "small", listOf(calls(AiToolCall("1", "a", "{}"))))
        val big = Scripted("p", "big", listOf(text("done")))
        val r = run(provider(small, big), GenerateTextRequest(
            model = AiModelId("p", "small"), messages = listOf(AiMessage.user("go")), tools = listOf(tool("a"), tool("b")),
            agent = AiAgentOptions(prepareStep = AiPrepareStep { ctx ->
                if (ctx.stepIndex == 2) AiStepOverrides(model = AiModelId("p", "big"), tools = emptyList()) else null
            }),
        ))
        assertEquals("done", r.text)
        assertEquals(listOf("small", "big"), r.steps.map { it.modelName })
        assertEquals("big", r.modelName)
        assertTrue(big.requests.single().tools.isEmpty())
        // 第二步的历史里有第一步的调用与结果
        assertEquals(listOf(AiRole.User, AiRole.Assistant, AiRole.Tool), big.requests.single().messages.map { it.role })
    }

    @Test
    fun callbacksObserveInOrderAndTheirFailuresDoNotBreakTheRun() = runTest {
        val log = mutableListOf<String>()
        val logLines = mutableListOf<String>()
        val m = Scripted("p", "m", listOf(calls(AiToolCall("1", "a", "{}")), text("done")))
        val cb = object : AiAgentCallbacks {
            override suspend fun onStepStart(context: AiStepContext) { log += "start${context.stepIndex}" }
            override suspend fun onToolCallStart(context: AiToolCallContext) { log += "tool:${context.call.name}"; error("observer bug") }
            override suspend fun onToolCallFinish(context: AiToolCallContext, result: AiToolResult) { log += "result:${result.content}" }
            override suspend fun onStepFinish(step: AiStep) { log += "finish${step.index}" }
        }
        val r = runToolLoop(
            GenerateTextRequest(model = AiModelId("p", "m"), messages = listOf(AiMessage.user("go")), tools = listOf(tool("a")), agent = AiAgentOptions(callbacks = cb)),
            DefaultProviderRegistry(mapOf("p" to provider(m))), DefaultModelRouter(RoutingConfig()), NoopAiUsageRecorder, logLines::add,
        )
        assertEquals("done", r.text)
        assertEquals(listOf("start1", "tool:a", "result:ok:a", "finish1", "start2", "finish2"), log)
        assertTrue(logLines.any { "onToolCallStart" in it })
    }

    @Test
    fun authorizerDenialBecomesAnErrorResultAndTheLoopContinues() = runTest {
        var executed = false
        val m = Scripted("p", "m", listOf(calls(AiToolCall("1", "delete", "{}")), text("sorry")))
        val r = run(provider(m), GenerateTextRequest(messages = listOf(AiMessage.user("go")),
            tools = listOf(tool("delete") { executed = true; "gone" }),
            agent = AiAgentOptions(principal = AiPrincipal("u1"), authorizer = AiToolAuthorizer { ctx ->
                if (ctx.principal?.id == "admin") AiToolDecision.Allow else AiToolDecision.Deny("not allowed for this user")
            })))
        assertFalse(executed)
        val result = r.toolResults.single()
        assertTrue(result.isError)
        assertTrue("tool_not_authorized" in result.content && "not allowed for this user" in result.content)
        assertEquals("sorry", r.text)
    }

    @Test
    fun pendingApprovalHandsTheStepBackWithoutExecutingAnything() = runTest {
        var executed = 0
        val m = Scripted("p", "m", listOf(calls(AiToolCall("1", "read", "{}"), AiToolCall("2", "pay", "{}"))))
        val r = run(provider(m), GenerateTextRequest(messages = listOf(AiMessage.user("go")),
            tools = listOf(tool("read") { executed++; "r" }, tool("pay") { executed++; "p" }),
            agent = AiAgentOptions(approver = AiToolApprover { ctx -> if (ctx.call.name == "pay") AiToolDecision.Pending else AiToolDecision.Allow })))
        assertEquals(0, executed)
        assertEquals(AiFinishReason.ToolCalls, r.finishReason)
        assertEquals(listOf("1", "2"), r.pendingToolCalls.map { it.id })
    }

    @Test
    fun parallelSafeToolsRunConcurrentlyAndKeepOrder() = runTest {
        var running = 0
        var peak = 0
        suspend fun slow(name: String): String { running++; peak = maxOf(peak, running); delay(10); running--; return name }
        val m = Scripted("p", "m", listOf(calls(AiToolCall("1", "a", "{}"), AiToolCall("2", "b", "{}"), AiToolCall("3", "c", "{}")), text("done")))
        val r = run(provider(m), GenerateTextRequest(messages = listOf(AiMessage.user("go")),
            tools = listOf(tool("a", true) { slow("a") }, tool("b", true) { slow("b") }, tool("c", true) { slow("c") }),
            agent = AiAgentOptions(maxParallelTools = 2)))
        assertEquals(2, peak)
        assertEquals(listOf("a", "b", "c"), r.toolResults.map { it.content })
        // 不声明 parallelSafe 时串行
        peak = 0
        val m2 = Scripted("p", "m", listOf(calls(AiToolCall("1", "a", "{}"), AiToolCall("2", "b", "{}")), text("done")))
        run(provider(m2), GenerateTextRequest(messages = listOf(AiMessage.user("go")),
            tools = listOf(tool("a") { slow("a") }, tool("b") { slow("b") }), agent = AiAgentOptions(maxParallelTools = 4)))
        assertEquals(1, peak)
    }

    @Test
    fun handlerSeesPrincipalAndRequestMetadata() = runTest {
        val m = Scripted("p", "m", listOf(calls(AiToolCall("1", "whoami", "{}")), text("done")))
        val whoami = AiToolDefinition("whoami", "", "{}", handler = AiToolHandler { ctx -> "${ctx.principal?.id}@${ctx.requestMetadata["tenant"]}#${ctx.stepIndex}" })
        val r = run(provider(m), GenerateTextRequest(messages = listOf(AiMessage.user("go")), tools = listOf(whoami),
            metadata = mapOf("tenant" to "t9"), agent = AiAgentOptions(principal = AiPrincipal("u7"))))
        assertEquals("u7@t9#1", r.toolResults.single().content)
    }

    @Test
    fun streamingEmitsStepsAndHonoursStopConditions() = runTest {
        val m = Scripted("p", "m", listOf(calls(AiToolCall("1", "finish", "{}")), text("never")))
        val p = provider(m)
        val events = runStreamingToolLoop(
            StreamTextRequest(model = AiModelId("p", "m"), messages = listOf(AiMessage.user("go")), tools = listOf(tool("finish")),
                agent = AiAgentOptions(stopWhen = listOf(AiStopCondition.hasToolCall("finish")))),
            DefaultProviderRegistry(mapOf("p" to p)), DefaultModelRouter(RoutingConfig()), NoopAiUsageRecorder,
        ).toList()
        assertEquals(1, events.filterIsInstance<AiStreamEvent.StepFinished>().size)
        val completed = events.last() as AiStreamEvent.Completed
        assertEquals(1, completed.steps.size)
        assertEquals(1, m.requests.size)
    }

    @Serializable data class Address(val city: String, val zip: String? = null)
    @Serializable enum class TempUnit { C, F }
    @Serializable data class WeatherQuery(val address: Address, val unit: TempUnit = TempUnit.C, val days: List<Int>, val tags: Map<String, Boolean> = emptyMap())

    @Test
    fun jsonSchemaFromSerialDescriptors() {
        val s = JsonSchemas.of<WeatherQuery>()
        assertEquals(JsonPrimitive("object"), s["type"])
        assertEquals(listOf("address", "days"), s["required"]!!.jsonArray.map { it.jsonPrimitive.content })
        val props = s["properties"]!!.jsonObject
        assertEquals(listOf("C", "F"), props["unit"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("integer", props["days"]!!.jsonObject["items"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("boolean", props["tags"]!!.jsonObject["additionalProperties"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        val zip = props["address"]!!.jsonObject["properties"]!!.jsonObject["zip"]!!.jsonObject
        assertEquals(JsonArray(listOf(JsonPrimitive("string"), JsonPrimitive("null"))), zip["type"])

        // strict：全部字段进 required，additionalProperties=false
        val strict = JsonSchemas.of<WeatherQuery>(strict = true)
        assertEquals(4, strict["required"]!!.jsonArray.size)
        assertEquals(JsonPrimitive(false), strict["additionalProperties"])

        assertFailsWith<IllegalArgumentException> { JsonSchemas.of<Map<Int, String>>() }
    }
}
