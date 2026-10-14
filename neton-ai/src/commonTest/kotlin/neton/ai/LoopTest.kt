package neton.ai

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import neton.ai.protocol.ir.FunctionTool
import neton.ai.protocol.ir.TextBlock
import neton.ai.protocol.ir.ToolCallBlock
import neton.ai.protocol.ir.ToolResultBlock
import neton.ai.testing.AiScript
import neton.ai.testing.ScriptedAiProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LoopTest {
    private val numberSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject { put("n", buildJsonObject { put("type", "integer") }) })
        put("required", kotlinx.serialization.json.buildJsonArray { add(JsonPrimitive("n")) })
        put("additionalProperties", false)
    }

    private fun tool(name: String, parallelSafe: Boolean = false, schema: JsonObject = AiTool.EMPTY_OBJECT_SCHEMA,
                     body: suspend (JsonElement) -> String = { "ok:$name" }) =
        AiTool(name, name, schema, parallelSafe = parallelSafe, execute = AiToolExecutor { input, _ -> AiToolOutput.Text(body(input)) })

    @Test
    fun singleStepAndToolLoop() = runTest {
        val p = ScriptedAiProvider(scripts = listOf(AiScript.toolCall("1", "now", text = "let me see"), AiScript.text("it is noon")))
        val r = p.client().generate { user("time?"); tool(tool("now") { "12:00" }) }
        assertEquals("it is noon", r.text)
        assertEquals(2, r.steps.size)
        assertEquals(listOf("12:00"), r.toolResults.map { it.output.asText() })
        // 第二步的请求：assistant（文本 + 调用）+ tool 结果
        val second = p.requests[1]
        assertEquals(TextBlock("let me see"), second.messages[1].content[0])
        assertEquals("12:00", (second.messages[2].content.single() as ToolResultBlock).content.filterIsInstance<TextBlock>().single().text)
        // responseMessages 可以直接接到历史后面
        assertEquals(listOf(AiRole.Assistant, AiRole.Tool, AiRole.Assistant), r.responseMessages.map { it.role })
        assertEquals(AiFinishReason.Stop, r.finishReason)
        assertEquals(4L, r.usage.inputTokens.let { it!! + r.usage.outputTokens!! })
    }

    @Test
    fun toolErrorsReachTheModelWithoutLeakingDetails() = runTest {
        val p = ScriptedAiProvider(scripts = listOf(AiScript.toolCalls(ToolCallBlock("1", "db", "{}"), ToolCallBlock("2", "city", "{}")), AiScript.text("sorry")))
        val logs = mutableListOf<String>()
        val ai = AiClient.create { provider(p); routing { defaultModel = "scripted:m" }; logSink = { logs += it } }
        val r = ai.generate {
            user("go")
            tool(tool("db") { error("connection to 10.0.0.5:5432 refused (password=hunter2)") })
            tool(tool("city") { throw AiToolException("unknown city, try another", "invalid_city") })
        }
        val outputs = r.toolResults.associate { it.toolName to it.output }
        assertTrue(outputs.values.all { it.isError })
        assertFalse("hunter2" in outputs.getValue("db").asText())
        assertTrue(AiToolFailure.GENERIC_CODE in outputs.getValue("db").asText())
        assertTrue("unknown city" in outputs.getValue("city").asText())
        assertTrue(logs.any { "hunter2" in it }, "details go to the log only")
        assertEquals("sorry", r.text)
    }

    @Test
    fun toolWithoutExecutorIsHandedBack() = runTest {
        val p = ScriptedAiProvider(scripts = listOf(AiScript.toolCall("c1", "remote")))
        val r = p.client().generate { user("go"); tool(AiTool("remote")) }
        assertEquals(1, r.steps.size)
        assertEquals(listOf("c1"), r.pendingToolCalls.map { it.id })
        assertEquals(AiFinishReason.ToolCalls, r.finishReason)
    }

    @Test
    fun stopConditionsAndDefaultLimit() = runTest {
        val looping = ScriptedAiProvider(scripts = listOf(AiScript.toolCall("x", "again")))
        val r = looping.client().generate { user("go"); tool(tool("again")) }
        assertEquals(20, r.steps.size, "default step limit")

        val p = ScriptedAiProvider(scripts = listOf(AiScript.toolCall("1", "search"), AiScript.toolCall("2", "finish"), AiScript.text("never")))
        val r2 = p.client().generate {
            user("go"); tools(tool("search"), tool("finish"))
            stopWhen(AiStopCondition.hasToolCall("finish"), AiStopCondition.stepCount(10))
        }
        assertEquals(2, r2.steps.size)
        assertEquals(2, p.requests.size)
    }

    @Test
    fun prepareStepSwapsModelToolsAndCarriesMessages() = runTest {
        val p = ScriptedAiProvider(scripts = listOf(AiScript.toolCall("1", "a"), AiScript.text("done")))
        val ai = AiClient.create { provider(p); routing { defaultModel = "scripted:small" } }
        val r = ai.generate {
            user("go"); tools(tool("a"), tool("b"))
            prepareStep = AiPrepareStep { ctx ->
                if (ctx.stepNumber == 2) AiStepOverrides(model = AiModelId("scripted", "big"), activeTools = emptySet(), instructions = "be brief") else null
            }
        }
        assertEquals("done", r.text)
        assertEquals(listOf("small", "big"), r.steps.map { it.model.modelName })
        assertEquals(listOf("a", "b"), p.requests[0].tools.map { (it as FunctionTool).name })
        assertTrue(p.requests[1].tools.isEmpty())
        assertEquals("be brief", p.requests[1].instructions.single().text)
    }

    @Test
    fun activeToolsAndToolOrder() = runTest {
        val p = ScriptedAiProvider(scripts = listOf(AiScript.text("ok")))
        p.client().generate { user("go"); tools(tool("zeta"), tool("alpha"), tool("mid")); toolOrder = listOf("mid") }
        assertEquals(listOf("mid", "alpha", "zeta"), p.requests[0].tools.map { (it as FunctionTool).name })
        val p2 = ScriptedAiProvider(scripts = listOf(AiScript.text("ok")))
        p2.client().generate { user("go"); tools(tool("a"), tool("b")); activeTools = setOf("b") }
        assertEquals(listOf("b"), p2.requests[0].tools.map { (it as FunctionTool).name })
    }

    @Test
    fun invalidCallsAreRepairedOrReturnedToTheModel() = runTest {
        // 参数不合法：不执行，以错误结果交给模型，模型改正后成功
        var executed = 0
        val p = ScriptedAiProvider(scripts = listOf(
            AiScript.toolCall("1", "square", """{"n":"two"}"""),
            AiScript.toolCall("2", "square", """{"n":2}"""),
            AiScript.text("4"),
        ))
        val r = p.client().generate { user("2^2?"); tool(tool("square", schema = numberSchema) { executed++; "4" }) }
        assertEquals("4", r.text)
        assertEquals(1, executed)
        val firstResult = r.steps[0].toolResults.single()
        assertTrue(firstResult.output.isError)
        assertTrue("\$.n must be integer" in firstResult.output.asText())
        assertTrue(r.steps[0].content.filterIsInstance<AiContent.ToolCall>().single().invalid)

        // 未知工具 + 修复
        val p2 = ScriptedAiProvider(scripts = listOf(AiScript.toolCall("1", "sqare", """{"n":3}"""), AiScript.text("9")))
        val r2 = p2.client().generate {
            user("3^2?"); tool(tool("square", schema = numberSchema) { "9" })
            repairToolCall = AiToolCallRepair { call, error, _, _ ->
                assertIs<AiToolCallError.NoSuchTool>(error)
                call.copy(name = "square")
            }
        }
        assertEquals("9", r2.toolResults.single().output.asText())
        assertEquals("square", r2.toolCalls.single().name)
    }

    @Test
    fun refineInputAndTypedTools() = runTest {
        @kotlinx.serialization.Serializable data class Q(val city: String)
        val p = ScriptedAiProvider(scripts = listOf(AiScript.toolCall("1", "weather", """{"city":"  oslo "}"""), AiScript.text("cold")))
        val weather = AiTools.typed<Q, Map<String, String>>("weather", "weather by city") { q, _ -> mapOf("city" to q.city, "temp" to "-3") }
        val refined = AiTool(weather.name, weather.description, weather.inputSchema, refineInput = { input ->
            JsonObject(mapOf("city" to JsonPrimitive((input.jsonObject["city"] as JsonPrimitive).content.trim().uppercase())))
        }, execute = weather.execute)
        val r = p.client().generate { user("weather?"); tool(refined) }
        assertEquals("""{"city":"OSLO"}""", r.toolCalls.single().argumentsJson)
        assertEquals("""{"city":"OSLO","temp":"-3"}""", r.toolResults.single().output.asText())
    }

    @Test
    fun parallelSafeToolsRunConcurrentlyInOrder() = runTest {
        var running = 0
        var peak = 0
        suspend fun slow(n: String): String { running++; peak = maxOf(peak, running); delay(10); running--; return n }
        val p = ScriptedAiProvider(scripts = listOf(AiScript.toolCalls(ToolCallBlock("1", "a", "{}"), ToolCallBlock("2", "b", "{}"), ToolCallBlock("3", "c", "{}")), AiScript.text("done")))
        val r = p.client().generate {
            user("go"); tools(tool("a", true) { slow("a") }, tool("b", true) { slow("b") }, tool("c", true) { slow("c") })
            toolCallConcurrency = 2
        }
        assertEquals(2, peak)
        assertEquals(listOf("a", "b", "c"), r.toolResults.map { it.output.asText() })
    }

    @Test
    fun toolTimeoutBecomesAnErrorResult() = runTest {
        val p = ScriptedAiProvider(scripts = listOf(AiScript.toolCall("1", "slow"), AiScript.text("gave up")))
        val r = p.client().generate { user("go"); tool(tool("slow") { delay(5_000); "late" }); timeout = AiTimeout(toolMs = 100) }
        assertTrue("tool_timeout" in r.toolResults.single().output.asText())
        assertEquals("gave up", r.text)
    }

    @Test
    fun retriesHonourRetryAfterThenSucceed() = runTest {
        val p = ScriptedAiProvider(scripts = listOf(
            AiScript.error(AiError.RateLimited(retryAfterMillis = 50, message = "slow down")),
            AiScript.error(AiError.ServerError(503, "busy")),
            AiScript.text("ok"),
        ))
        val start = testScheduler.currentTime
        val r = p.client().generate { user("go") }
        assertEquals("ok", r.text)
        // 第一次按 Retry-After 50ms，第二次按退避 4s（第一次退避 2s 已被 Retry-After 替代）
        assertEquals(50L + 4_000L, testScheduler.currentTime - start)

        val p2 = ScriptedAiProvider(scripts = listOf(AiScript.error(AiError.Unauthorized("bad key")), AiScript.text("never")))
        assertIs<AiError.Unauthorized>(assertFailsWith<AiException> { p2.client().generate { user("go") } }.error)
        assertEquals(1, p2.requests.size, "non-retryable errors are not retried")
    }

    @Test
    fun policyFallsBackOnlyBeforeAnyStepCompletes() = runTest {
        val primary = ScriptedAiProvider("primary", listOf(AiScript.error(AiError.ServerError(500, "down"))))
        val backup = ScriptedAiProvider("backup", listOf(AiScript.text("from backup")))
        val ai = AiClient.create {
            provider(primary); provider(backup)
            routing { policy("chat") { prefer("primary:m"); fallback("backup:m") } }
        }
        val r = ai.generate { user("go"); modelPolicy = "chat"; maxRetries = 0 }
        assertEquals("from backup", r.text)
        assertEquals("backup", r.model?.providerId)
    }

    @Test
    fun toolChoiceIsEnforced() = runTest {
        val p = ScriptedAiProvider(scripts = listOf(AiScript.text("I won't call it")))
        val e = assertFailsWith<AiException> { p.client().generate { user("go"); tool(tool("must")); toolChoice = ToolChoice.Required } }
        assertIs<AiError.ToolChoiceViolation>(e.error)
    }

    @Test
    fun truncatedStepsDoNotExecuteTools() = runTest {
        var executed = false
        val p = ScriptedAiProvider(scripts = listOf(AiScript.Reply(listOf(ToolCallBlock("1", "t", "{}")), finishReason = neton.ai.protocol.ir.FinishReason.Length)))
        val r = p.client().generate { user("go"); tool(tool("t") { executed = true; "x" }) }
        assertFalse(executed)
        assertEquals(AiFinishReason.Length, r.finishReason)
    }

    @Test
    fun callbacksObserveInOrderAndFailuresAreSwallowed() = runTest {
        val log = mutableListOf<String>()
        val p = ScriptedAiProvider(scripts = listOf(AiScript.toolCall("1", "a"), AiScript.text("done")))
        val r = p.client().generate {
            user("go"); tool(tool("a"))
            callbacks = object : AiCallbacks {
                override suspend fun onStart(request: AiRequest) { log += "start" }
                override suspend fun onStepStart(context: AiStepContext) { log += "step${context.stepNumber}" }
                override suspend fun onModelCallEnd(stepNumber: Int, model: AiModelId, usage: AiUsage, finishReason: AiFinishReason) { log += "model$stepNumber" }
                override suspend fun onToolExecutionStart(context: AiToolCallContext) { log += "tool"; error("observer bug") }
                override suspend fun onToolExecutionEnd(context: AiToolCallContext, output: AiToolOutput, durationMs: Long) { log += "toolEnd" }
                override suspend fun onStepEnd(step: AiStep) { log += "end${step.stepNumber}" }
                override suspend fun onEnd(result: AiResult) { log += "done" }
            }
        }
        assertEquals("done", r.text)
        assertEquals(listOf("start", "step1", "model1", "tool", "toolEnd", "end1", "step2", "model2", "end2", "done"), log)
    }

    @Test
    fun toolSearchRevealsDeferredToolsOnTheNextStep() = runTest {
        val p = ScriptedAiProvider(scripts = listOf(
            AiScript.toolCall("1", AiTools.TOOL_SEARCH_NAME, """{"query":"weather forecast"}"""),
            AiScript.toolCall("2", "getWeatherForecast", "{}"),
            AiScript.text("sunny"),
        ))
        val r = p.client().generate {
            user("weather?")
            tool(AiTools.toolSearch())
            tool(AiTool("getWeatherForecast", "Get the weather forecast", deferLoading = true, execute = AiToolExecutor { _, _ -> AiToolOutput.Text("sunny") }))
            tool(AiTool("sendEmail", "Send an email", deferLoading = true, execute = AiToolExecutor { _, _ -> AiToolOutput.Text("sent") }))
        }
        assertEquals("sunny", r.text)
        assertEquals(listOf(AiTools.TOOL_SEARCH_NAME), p.requests[0].tools.map { (it as FunctionTool).name })
        assertEquals(setOf(AiTools.TOOL_SEARCH_NAME, "getWeatherForecast"), p.requests[1].tools.map { (it as FunctionTool).name }.toSet())
    }

    @Test
    fun warningsAndPerformanceAreReported() = runTest {
        val w = neton.ai.model.AiWarning(neton.ai.model.AiWarning.Type.UNSUPPORTED, "seed", "not supported")
        val p = ScriptedAiProvider(scripts = listOf(AiScript.Reply(listOf(TextBlock("ok")), warnings = listOf(w))))
        val r = p.client().generate { user("go") }
        assertEquals(listOf(w), r.warnings)
        assertTrue(r.steps.single().performance.stepTimeMs >= 0)
    }
}
