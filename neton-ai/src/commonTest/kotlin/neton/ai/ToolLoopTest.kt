// neton-ai/src/commonTest/kotlin/neton/ai/ToolLoopTest.kt
package neton.ai

import kotlinx.coroutines.test.runTest
import neton.ai.internal.DefaultModelRouter
import neton.ai.internal.DefaultProviderRegistry
import neton.ai.internal.runToolLoop
import neton.ai.provider.AiProvider
import neton.ai.provider.AiTextModel
import neton.ai.provider.ProviderCallRequest
import neton.ai.provider.ProviderCallResponse
import neton.ai.routing.RoutingConfig
import neton.ai.usage.AiUsageEvent
import neton.ai.usage.AiUsageRecorder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ToolLoopTest {

    // ---- Test fixtures ----

    private class ScriptedTextModel(
        override val providerId: String,
        override val modelName: String,
        private val script: List<ScriptStep>,
    ) : AiTextModel {
        private var idx = 0
        var calls = 0; private set
        var lastRequest: ProviderCallRequest? = null; private set
        override suspend fun generate(request: ProviderCallRequest): ProviderCallResponse {
            calls++
            lastRequest = request
            val step = script[idx++]
            return step.invoke()
        }
    }

    private sealed interface ScriptStep {
        fun invoke(): ProviderCallResponse
    }

    private data class Reply(val response: ProviderCallResponse) : ScriptStep {
        override fun invoke() = response
    }

    private data class Fail(val error: AiError) : ScriptStep {
        override fun invoke(): Nothing = throw AiException(error)
    }

    private class ScriptedProvider(
        override val id: String,
        private val model: ScriptedTextModel,
    ) : AiProvider {
        override fun textModel(modelName: String): AiTextModel? =
            if (modelName == model.modelName) model else null
        override fun streamingTextModel(modelName: String) = null
        override fun embeddingModel(modelName: String) = null
    }

    private class CapturingRecorder : AiUsageRecorder {
        val events = mutableListOf<AiUsageEvent>()
        override suspend fun record(event: AiUsageEvent) { events += event }
    }

    private fun textReply(text: String, calls: List<AiToolCall> = emptyList(),
                          usage: AiUsage? = AiUsage(10, 5, 15),
                          finish: AiFinishReason = AiFinishReason.Stop) = Reply(
        ProviderCallResponse(
            message = AiMessage(
                role = AiRole.Assistant,
                content = if (calls.isEmpty()) listOf(AiContent.Text(text)) else emptyList(),
                toolCalls = calls,
            ),
            text = if (calls.isEmpty()) text else "",
            toolCalls = calls,
            usage = usage,
            finishReason = if (calls.isEmpty()) finish else AiFinishReason.ToolCalls,
        )
    )

    private fun runLoop(
        request: GenerateTextRequest,
        providerId: String = "p1",
        modelName: String = "m1",
        script: List<ScriptStep>,
        recorder: AiUsageRecorder = CapturingRecorder(),
        extraProviders: Map<String, AiProvider> = emptyMap(),
        defaultModel: String? = "$providerId:$modelName",
    ): GenerateTextResult {
        val model = ScriptedTextModel(providerId, modelName, script)
        val providers = mapOf(providerId to ScriptedProvider(providerId, model)) + extraProviders
        return kotlinx.coroutines.runBlocking {
            runToolLoop(
                request = request,
                registry = DefaultProviderRegistry(providers),
                router = DefaultModelRouter(RoutingConfig(defaultModel = defaultModel?.let(AiModelId::parse))),
                recorder = recorder,
            )
        }
    }

    // ---- Tests ----

    @Test fun singleRoundNoToolCalls() = runTest {
        val res = runLoop(
            request = GenerateTextRequest(messages = listOf(AiMessage(AiRole.User, listOf(AiContent.Text("hi"))))),
            script = listOf(textReply("Hello world")),
        )
        assertEquals("Hello world", res.text)
        assertEquals(AiFinishReason.Stop, res.finishReason)
        assertEquals(1, res.rounds)
        assertTrue(res.toolCalls.isEmpty())
        assertTrue(res.toolResults.isEmpty())
        assertEquals(AiUsage(10, 5, 15), res.usage)
    }

    @Test fun toolLoopExecutesLocalToolAndContinues() = runTest {
        val req = GenerateTextRequest(
            messages = listOf(AiMessage(AiRole.User, listOf(AiContent.Text("balance?")))),
            tools = listOf(AiToolDefinition(
                name = "get_balance",
                description = "",
                inputSchemaJson = "{}",
                executor = AiToolExecutor { _ -> """{"balance":42}""" },
            )),
        )
        val res = runLoop(
            request = req,
            script = listOf(
                textReply("", calls = listOf(AiToolCall("c1", "get_balance", """{"userId":7}"""))),
                textReply("You have 42."),
            ),
        )
        assertEquals("You have 42.", res.text)
        assertEquals(2, res.rounds)
        assertEquals(1, res.toolCalls.size)
        assertEquals(1, res.toolResults.size)
        assertEquals("""{"balance":42}""", res.toolResults[0].content)
        assertEquals(false, res.toolResults[0].isError)
        // Usage aggregated across 2 rounds
        assertEquals(AiUsage(20, 10, 30), res.usage)
    }

    @Test fun toolExecutorErrorMarksResultAsError() = runTest {
        val req = GenerateTextRequest(
            messages = listOf(AiMessage(AiRole.User, listOf(AiContent.Text("call")))),
            tools = listOf(AiToolDefinition(
                name = "bad_tool",
                description = "",
                inputSchemaJson = "{}",
                executor = AiToolExecutor { _ -> error("boom") },
            )),
        )
        val res = runLoop(
            request = req,
            script = listOf(
                textReply("", calls = listOf(AiToolCall("c1", "bad_tool", "{}"))),
                textReply("recovered"),
            ),
        )
        assertEquals("recovered", res.text)
        assertEquals(true, res.toolResults.single().isError)
        assertTrue("boom" in res.toolResults.single().content)
    }

    @Test fun toolCallWithoutExecutorReturnsEarly() = runTest {
        val req = GenerateTextRequest(
            messages = listOf(AiMessage(AiRole.User, listOf(AiContent.Text("call")))),
            tools = listOf(AiToolDefinition(
                name = "remote_tool",
                description = "",
                inputSchemaJson = "{}",
                executor = null,  // no local executor
            )),
        )
        val res = runLoop(
            request = req,
            script = listOf(
                textReply("", calls = listOf(AiToolCall("c1", "remote_tool", "{}"))),
            ),
        )
        assertEquals(AiFinishReason.ToolCalls, res.finishReason)
        assertEquals(1, res.rounds)
        assertEquals(1, res.toolCalls.size)
        assertTrue(res.toolResults.isEmpty(), "no local execution happened")
    }

    @Test fun maxToolRoundsExhaustedReturnsToolCallsFinish() = runTest {
        val req = GenerateTextRequest(
            messages = listOf(AiMessage(AiRole.User, listOf(AiContent.Text("loop")))),
            maxToolRounds = 2,
            tools = listOf(AiToolDefinition(
                name = "ping", description = "", inputSchemaJson = "{}",
                executor = AiToolExecutor { _ -> "pong" },
            )),
        )
        // Model keeps calling tool forever
        val res = runLoop(
            request = req,
            script = listOf(
                textReply("", calls = listOf(AiToolCall("c1", "ping", "{}"))),
                textReply("", calls = listOf(AiToolCall("c2", "ping", "{}"))),
            ),
        )
        assertEquals(AiFinishReason.ToolCalls, res.finishReason)
        assertEquals(2, res.rounds)
        assertEquals(2, res.toolCalls.size)
        assertEquals(2, res.toolResults.size)
    }

    @Test fun round1FallbackEligibleErrorTriesNextCandidate() = runTest {
        // Two providers; first throws RateLimited at round 1, second succeeds
        val r1Model = ScriptedTextModel("p1", "m1", listOf(Fail(AiError.RateLimited(null, "429"))))
        val r2Model = ScriptedTextModel("p2", "m2", listOf(textReply("recovered").let { (it as Reply).response }.let { Reply(it) }))
        val providers = mapOf(
            "p1" to ScriptedProvider("p1", r1Model),
            "p2" to ScriptedProvider("p2", r2Model),
        )
        val router = DefaultModelRouter(RoutingConfig(
            policies = mapOf("any" to neton.ai.routing.ModelPolicy(
                prefer = listOf(AiModelId("p1", "m1")),
                fallback = listOf(AiModelId("p2", "m2")),
            ))
        ))
        val recorder = CapturingRecorder()
        val res = kotlinx.coroutines.runBlocking {
            neton.ai.internal.runToolLoop(
                request = GenerateTextRequest(
                    modelPolicy = "any",
                    messages = listOf(AiMessage(AiRole.User, listOf(AiContent.Text("x")))),
                ),
                registry = DefaultProviderRegistry(providers),
                router = router,
                recorder = recorder,
            )
        }
        assertEquals("p2", res.providerId)
        assertEquals("m2", res.modelName)
        assertEquals("recovered", res.text)
        // Recorder should have seen failure record for p1 then success for p2
        assertEquals(2, recorder.events.size)
        assertEquals(false, recorder.events[0].success)
        assertEquals("RateLimited", recorder.events[0].errorKind)
        assertEquals(true, recorder.events[1].success)
    }

    @Test fun round2FailureNotFallbackEligibleEvenIfEligibleError() = runTest {
        // First model: round 1 returns tool_call, round 2 throws fallback-eligible error
        // Even though Network is fallback-eligible, round >= 2 must not fall back (state accumulated)
        val r1Model = ScriptedTextModel("p1", "m1", listOf(
            Reply(ProviderCallResponse(
                message = AiMessage(AiRole.Assistant, emptyList(), listOf(AiToolCall("c1", "t", "{}"))),
                text = "",
                toolCalls = listOf(AiToolCall("c1", "t", "{}")),
                usage = null, finishReason = AiFinishReason.ToolCalls,
            )),
            Fail(AiError.Network("boom", null)),
        ))
        val r2Model = ScriptedTextModel("p2", "m2", listOf(textReply("would-have-recovered").let { (it as Reply).response }.let { Reply(it) }))
        val providers = mapOf(
            "p1" to ScriptedProvider("p1", r1Model),
            "p2" to ScriptedProvider("p2", r2Model),
        )
        val router = DefaultModelRouter(RoutingConfig(
            policies = mapOf("x" to neton.ai.routing.ModelPolicy(
                prefer = listOf(AiModelId("p1", "m1")),
                fallback = listOf(AiModelId("p2", "m2")),
            ))
        ))
        val req = GenerateTextRequest(
            modelPolicy = "x",
            messages = listOf(AiMessage(AiRole.User, listOf(AiContent.Text("x")))),
            tools = listOf(AiToolDefinition("t", "", "{}", AiToolExecutor { _ -> "ok" })),
        )
        val ex = assertFailsWith<AiException> {
            kotlinx.coroutines.runBlocking {
                neton.ai.internal.runToolLoop(req, DefaultProviderRegistry(providers), router, CapturingRecorder())
            }
        }
        assertTrue(ex.error is AiError.Network)
        assertEquals(0, r2Model.calls, "fallback should NOT have been attempted after round 2 failure")
    }

    @Test fun nonFallbackEligibleErrorPropagatesImmediately() = runTest {
        val ex = assertFailsWith<AiException> {
            runLoop(
                request = GenerateTextRequest(messages = listOf(AiMessage(AiRole.User, listOf(AiContent.Text("x"))))),
                script = listOf(Fail(AiError.Unauthorized("bad key"))),
            )
        }
        assertTrue(ex.error is AiError.Unauthorized)
    }

    @Test fun modelNotFoundWhenProviderMissing() = runTest {
        val ex = assertFailsWith<AiException> {
            runLoop(
                request = GenerateTextRequest(
                    model = AiModelId("missing", "x"),
                    messages = listOf(AiMessage(AiRole.User, listOf(AiContent.Text("x")))),
                ),
                script = emptyList(),
                defaultModel = null,
            )
        }
        assertTrue(ex.error is AiError.ModelNotFound)
    }

    @Test fun usageRecorderCalledPerRound() = runTest {
        val req = GenerateTextRequest(
            messages = listOf(AiMessage(AiRole.User, listOf(AiContent.Text("hi")))),
            metadata = mapOf("requestId" to "r-42", "userId" to "u1"),
            tools = listOf(AiToolDefinition("t", "", "{}", AiToolExecutor { _ -> "ok" })),
        )
        val recorder = CapturingRecorder()
        runLoop(
            request = req,
            script = listOf(
                textReply("", calls = listOf(AiToolCall("c1", "t", "{}"))),
                textReply("done"),
            ),
            recorder = recorder,
        )
        assertEquals(2, recorder.events.size)
        // Both events get the same requestId and request metadata
        assertEquals("r-42", recorder.events[0].requestId)
        assertEquals("r-42", recorder.events[1].requestId)
        assertEquals(mapOf("requestId" to "r-42", "userId" to "u1"), recorder.events[0].requestMetadata)
        // Round numbers
        assertEquals(1, recorder.events[0].round)
        assertEquals(2, recorder.events[1].round)
        // Both success
        assertTrue(recorder.events.all { it.success })
    }
}
