// neton-ai/src/commonTest/kotlin/neton/ai/StreamingToolLoopTest.kt
package neton.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import neton.ai.internal.DefaultModelRouter
import neton.ai.internal.DefaultProviderRegistry
import neton.ai.internal.runStreamingToolLoop
import neton.ai.provider.AiProvider
import neton.ai.provider.AiStreamingTextModel
import neton.ai.provider.AiTextModel
import neton.ai.provider.ProviderCallRequest
import neton.ai.provider.ProviderCallResponse
import neton.ai.routing.ModelPolicy
import neton.ai.routing.RoutingConfig
import neton.ai.usage.AiUsageEvent
import neton.ai.usage.AiUsageRecorder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

// ---------------------------------------------------------------------------
// Test fixtures
// ---------------------------------------------------------------------------

/** ScriptedStreamingModel replays a scripted Flow<AiStreamEvent> on demand. */
private class ScriptedStreamingModel(
    override val providerId: String,
    override val modelName: String,
    /** Each entry is the events for one call to stream(). Consumed in order. */
    private val rounds: List<Flow<AiStreamEvent>>,
) : AiStreamingTextModel {
    private var callIdx = 0
    var calls = 0; private set
    var lastRequest: ProviderCallRequest? = null; private set

    override suspend fun generate(request: ProviderCallRequest): ProviderCallResponse =
        error("ScriptedStreamingModel.generate() should not be called in streaming tests")

    override fun stream(request: ProviderCallRequest): Flow<AiStreamEvent> {
        calls++
        lastRequest = request
        return rounds[callIdx++]
    }
}

private class ScriptedStreamingProvider(
    override val id: String,
    private val model: ScriptedStreamingModel,
) : AiProvider {
    override fun textModel(modelName: String): AiTextModel? = null
    override fun streamingTextModel(modelName: String): AiStreamingTextModel? =
        if (modelName == model.modelName) model else null
    override fun embeddingModel(modelName: String) = null
}

private class CapturingRecorder : AiUsageRecorder {
    val events = mutableListOf<AiUsageEvent>()
    override suspend fun record(event: AiUsageEvent) { events += event }
}

/** Build a Completed event for test convenience. */
private fun completedEvent(
    text: String,
    toolCalls: List<AiToolCall> = emptyList(),
    usage: AiUsage? = AiUsage(10, 5, 15),
    finishReason: AiFinishReason = if (toolCalls.isEmpty()) AiFinishReason.Stop else AiFinishReason.ToolCalls,
) = AiStreamEvent.Completed(
    message = AiMessage(
        role = AiRole.Assistant,
        content = if (toolCalls.isEmpty()) listOf(AiContent.Text(text)) else emptyList(),
        toolCalls = toolCalls,
    ),
    text = text,
    usage = usage,
    finishReason = finishReason,
)

/** Convenience: build a scripted Flow from a list of events. */
private fun scriptedFlow(vararg events: AiStreamEvent): Flow<AiStreamEvent> =
    events.toList().asFlow()

/** Convenience: build a Flow that throws an AiException before emitting anything. */
private fun failingFlow(error: AiError): Flow<AiStreamEvent> = flow {
    throw AiException(error)
}

/** Run the streaming tool loop and collect all events. */
private suspend fun collectLoop(
    request: StreamTextRequest,
    registry: DefaultProviderRegistry,
    router: DefaultModelRouter,
    recorder: AiUsageRecorder = CapturingRecorder(),
): List<AiStreamEvent> = runStreamingToolLoop(request, registry, router, recorder).toList()

/** Build a single-provider setup. */
private fun singleProviderSetup(
    providerId: String = "p1",
    modelName: String = "m1",
    rounds: List<Flow<AiStreamEvent>>,
    modelPolicy: String? = null,
): Triple<StreamTextRequest, DefaultProviderRegistry, DefaultModelRouter> {
    val model = ScriptedStreamingModel(providerId, modelName, rounds)
    val provider = ScriptedStreamingProvider(providerId, model)
    val registry = DefaultProviderRegistry(mapOf(providerId to provider))
    val modelId = AiModelId(providerId, modelName)
    val router = DefaultModelRouter(RoutingConfig(
        defaultModel = if (modelPolicy == null) modelId else null,
        policies = if (modelPolicy != null) mapOf(modelPolicy to ModelPolicy(listOf(modelId), emptyList())) else emptyMap(),
    ))
    return Triple(
        StreamTextRequest(
            model = if (modelPolicy == null) modelId else null,
            modelPolicy = modelPolicy,
            messages = listOf(AiMessage(AiRole.User, listOf(AiContent.Text("hello")))),
        ),
        registry,
        router,
    )
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

class StreamingToolLoopTest {

    /**
     * Test 1: single-round text stream — provider emits TextDelta + Completed.
     * AiClient must inject Started before the TextDelta, then pass through Completed.
     */
    @Test
    fun singleRoundTextStreamEmitsStartedThenDeltasThenCompleted() = runTest {
        val events = scriptedFlow(
            AiStreamEvent.TextDelta("Hello"),
            completedEvent("Hello"),
        )
        val (request, registry, router) = singleProviderSetup(rounds = listOf(events))

        val result = collectLoop(request, registry, router)

        assertEquals(3, result.size, "Expected Started + TextDelta + Completed")
        assertIs<AiStreamEvent.Started>(result[0])
        assertEquals("p1", (result[0] as AiStreamEvent.Started).providerId)
        assertEquals("m1", (result[0] as AiStreamEvent.Started).modelName)
        assertIs<AiStreamEvent.TextDelta>(result[1])
        assertEquals("Hello", (result[1] as AiStreamEvent.TextDelta).text)
        assertIs<AiStreamEvent.Completed>(result[2])
        assertEquals(AiFinishReason.Stop, (result[2] as AiStreamEvent.Completed).finishReason)
    }

    /**
     * Test 2: tool loop — round 1 emits tool call, executor returns "ok", round 2 emits text + Completed.
     * Sequence: Started + ToolCallReady + ToolResultReady + TextDelta + Completed
     */
    @Test
    fun toolLoopExecutesLocalToolThenContinues() = runTest {
        val toolCall = AiToolCall("c1", "my_tool", "{}")
        val round1 = scriptedFlow(
            AiStreamEvent.ToolCallReady(toolCall),
            completedEvent("", toolCalls = listOf(toolCall)),
        )
        val round2 = scriptedFlow(
            AiStreamEvent.TextDelta("done"),
            completedEvent("done"),
        )
        val model = ScriptedStreamingModel("p1", "m1", listOf(round1, round2))
        val provider = ScriptedStreamingProvider("p1", model)
        val registry = DefaultProviderRegistry(mapOf("p1" to provider))
        val router = DefaultModelRouter(RoutingConfig(defaultModel = AiModelId("p1", "m1")))

        val request = StreamTextRequest(
            model = AiModelId("p1", "m1"),
            messages = listOf(AiMessage(AiRole.User, listOf(AiContent.Text("call tool")))),
            tools = listOf(AiToolDefinition(
                name = "my_tool",
                description = "test tool",
                inputSchemaJson = "{}",
                executor = AiToolExecutor { _ -> "ok" },
            )),
        )

        val result = collectLoop(request, registry, router)

        // Expected: Started, ToolCallReady, ToolResultReady, TextDelta, Completed
        assertIs<AiStreamEvent.Started>(result[0])
        assertIs<AiStreamEvent.ToolCallReady>(result[1])
        assertEquals("c1", (result[1] as AiStreamEvent.ToolCallReady).call.id)
        assertIs<AiStreamEvent.ToolResultReady>(result[2])
        val toolResult = (result[2] as AiStreamEvent.ToolResultReady).result
        assertEquals("ok", toolResult.content)
        assertFalse(toolResult.isError)
        assertIs<AiStreamEvent.TextDelta>(result[3])
        assertEquals("done", (result[3] as AiStreamEvent.TextDelta).text)
        assertIs<AiStreamEvent.Completed>(result[4])
        assertEquals(AiFinishReason.Stop, (result[4] as AiStreamEvent.Completed).finishReason)
        assertEquals(5, result.size)
        // Provider must have been called twice (2 rounds)
        assertEquals(2, model.calls)
    }

    /**
     * Test 3: maxToolRounds exhausted — model keeps calling the tool every round.
     * After maxToolRounds rounds, AiClient emits Completed(finishReason=ToolCalls).
     */
    @Test
    fun maxToolRoundsExhaustedEmitsCompletedWithToolCallsFinish() = runTest {
        val toolCall1 = AiToolCall("c1", "ping", "{}")
        val toolCall2 = AiToolCall("c2", "ping", "{}")
        val round1 = scriptedFlow(
            AiStreamEvent.ToolCallReady(toolCall1),
            completedEvent("", toolCalls = listOf(toolCall1)),
        )
        val round2 = scriptedFlow(
            AiStreamEvent.ToolCallReady(toolCall2),
            completedEvent("", toolCalls = listOf(toolCall2)),
        )
        val model = ScriptedStreamingModel("p1", "m1", listOf(round1, round2))
        val provider = ScriptedStreamingProvider("p1", model)
        val registry = DefaultProviderRegistry(mapOf("p1" to provider))
        val router = DefaultModelRouter(RoutingConfig(defaultModel = AiModelId("p1", "m1")))

        val request = StreamTextRequest(
            model = AiModelId("p1", "m1"),
            messages = listOf(AiMessage(AiRole.User, listOf(AiContent.Text("loop")))),
            maxToolRounds = 2,
            tools = listOf(AiToolDefinition(
                name = "ping",
                description = "",
                inputSchemaJson = "{}",
                executor = AiToolExecutor { _ -> "pong" },
            )),
        )

        val result = collectLoop(request, registry, router)

        // Started + round1 events + ToolResultReady + round2 events + ToolResultReady + Completed(ToolCalls)
        val started = result.filterIsInstance<AiStreamEvent.Started>()
        assertEquals(1, started.size)
        val completed = result.filterIsInstance<AiStreamEvent.Completed>()
        assertEquals(1, completed.size)
        assertEquals(AiFinishReason.ToolCalls, completed[0].finishReason)
        // No Failed events
        assertTrue(result.filterIsInstance<AiStreamEvent.Failed>().isEmpty())
        assertEquals(2, model.calls)
    }

    /**
     * Test 4: tool def has no executor — AiClient emits Completed(ToolCalls) immediately and returns.
     */
    @Test
    fun toolWithoutExecutorReturnsEarlyWithToolCallsFinish() = runTest {
        val toolCall = AiToolCall("c1", "remote_tool", "{}")
        val round1 = scriptedFlow(
            AiStreamEvent.ToolCallReady(toolCall),
            completedEvent("", toolCalls = listOf(toolCall)),
        )
        val model = ScriptedStreamingModel("p1", "m1", listOf(round1))
        val provider = ScriptedStreamingProvider("p1", model)
        val registry = DefaultProviderRegistry(mapOf("p1" to provider))
        val router = DefaultModelRouter(RoutingConfig(defaultModel = AiModelId("p1", "m1")))

        val request = StreamTextRequest(
            model = AiModelId("p1", "m1"),
            messages = listOf(AiMessage(AiRole.User, listOf(AiContent.Text("call")))),
            tools = listOf(AiToolDefinition(
                name = "remote_tool",
                description = "",
                inputSchemaJson = "{}",
                executor = null, // no local executor
            )),
        )

        val result = collectLoop(request, registry, router)

        assertIs<AiStreamEvent.Started>(result[0])
        assertIs<AiStreamEvent.ToolCallReady>(result[1])
        val completed = result.filterIsInstance<AiStreamEvent.Completed>()
        assertEquals(1, completed.size)
        assertEquals(AiFinishReason.ToolCalls, completed[0].finishReason)
        // No ToolResultReady since no executor
        assertTrue(result.filterIsInstance<AiStreamEvent.ToolResultReady>().isEmpty())
        // Only 1 round called
        assertEquals(1, model.calls)
    }

    /**
     * Test 5: tool executor throws — ToolResultReady(isError=true) emitted, loop continues to next round.
     */
    @Test
    fun toolExecutorErrorMarksToolResultAsError() = runTest {
        val toolCall = AiToolCall("c1", "bad_tool", "{}")
        val round1 = scriptedFlow(
            AiStreamEvent.ToolCallReady(toolCall),
            completedEvent("", toolCalls = listOf(toolCall)),
        )
        val round2 = scriptedFlow(
            AiStreamEvent.TextDelta("recovered"),
            completedEvent("recovered"),
        )
        val model = ScriptedStreamingModel("p1", "m1", listOf(round1, round2))
        val provider = ScriptedStreamingProvider("p1", model)
        val registry = DefaultProviderRegistry(mapOf("p1" to provider))
        val router = DefaultModelRouter(RoutingConfig(defaultModel = AiModelId("p1", "m1")))

        val request = StreamTextRequest(
            model = AiModelId("p1", "m1"),
            messages = listOf(AiMessage(AiRole.User, listOf(AiContent.Text("call")))),
            tools = listOf(AiToolDefinition(
                name = "bad_tool",
                description = "",
                inputSchemaJson = "{}",
                executor = AiToolExecutor { _ -> error("boom") },
            )),
        )

        val result = collectLoop(request, registry, router)

        assertIs<AiStreamEvent.Started>(result[0])
        assertIs<AiStreamEvent.ToolCallReady>(result[1])
        val toolResultReady = result.filterIsInstance<AiStreamEvent.ToolResultReady>()
        assertEquals(1, toolResultReady.size)
        assertTrue(toolResultReady[0].result.isError, "Executor error should mark result as isError=true")
        assertTrue("boom" in toolResultReady[0].result.content)
        val completed = result.filterIsInstance<AiStreamEvent.Completed>()
        assertEquals(1, completed.size)
        assertEquals(AiFinishReason.Stop, completed[0].finishReason)
        assertEquals(2, model.calls)
    }

    /**
     * Test 6: fallback before first event — provider1 throws RateLimited before any event,
     * AiClient falls back to provider2. Started should emit provider2's id/model.
     */
    @Test
    fun fallbackBeforeFirstEventTriesNextCandidate() = runTest {
        val model1 = ScriptedStreamingModel("p1", "m1", listOf(failingFlow(AiError.RateLimited(null, "429"))))
        val model2 = ScriptedStreamingModel("p2", "m2", listOf(
            scriptedFlow(
                AiStreamEvent.TextDelta("ok"),
                completedEvent("ok"),
            )
        ))
        val provider1 = ScriptedStreamingProvider("p1", model1)
        val provider2 = ScriptedStreamingProvider("p2", model2)
        val registry = DefaultProviderRegistry(mapOf("p1" to provider1, "p2" to provider2))
        val router = DefaultModelRouter(RoutingConfig(
            policies = mapOf("any" to ModelPolicy(
                prefer = listOf(AiModelId("p1", "m1")),
                fallback = listOf(AiModelId("p2", "m2")),
            ))
        ))

        val request = StreamTextRequest(
            modelPolicy = "any",
            messages = listOf(AiMessage(AiRole.User, listOf(AiContent.Text("x")))),
        )

        val result = collectLoop(request, registry, router)

        val started = result.filterIsInstance<AiStreamEvent.Started>()
        assertEquals(1, started.size)
        // Started should be from provider2, not provider1
        assertEquals("p2", started[0].providerId)
        assertEquals("m2", started[0].modelName)

        val failed = result.filterIsInstance<AiStreamEvent.Failed>()
        assertTrue(failed.isEmpty(), "No Failed event should be emitted when fallback succeeds")

        val completed = result.filterIsInstance<AiStreamEvent.Completed>()
        assertEquals(1, completed.size)
        assertEquals(AiFinishReason.Stop, completed[0].finishReason)
    }

    @Test
    fun fallbackWhenFirstProviderEventIsFailed() = runTest {
        val model1 = ScriptedStreamingModel("p1", "m1", listOf(
            scriptedFlow(AiStreamEvent.Failed(AiError.RateLimited(null, "429")))
        ))
        val model2 = ScriptedStreamingModel("p2", "m2", listOf(
            scriptedFlow(
                AiStreamEvent.TextDelta("ok"),
                completedEvent("ok"),
            )
        ))
        val provider1 = ScriptedStreamingProvider("p1", model1)
        val provider2 = ScriptedStreamingProvider("p2", model2)
        val registry = DefaultProviderRegistry(mapOf("p1" to provider1, "p2" to provider2))
        val router = DefaultModelRouter(RoutingConfig(
            policies = mapOf("any" to ModelPolicy(
                prefer = listOf(AiModelId("p1", "m1")),
                fallback = listOf(AiModelId("p2", "m2")),
            ))
        ))
        val recorder = CapturingRecorder()

        val result = collectLoop(
            request = StreamTextRequest(
                modelPolicy = "any",
                messages = listOf(AiMessage(AiRole.User, listOf(AiContent.Text("x")))),
            ),
            registry = registry,
            router = router,
            recorder = recorder,
        )

        val started = result.filterIsInstance<AiStreamEvent.Started>()
        assertEquals(1, started.size)
        assertEquals("p2", started[0].providerId)
        assertTrue(result.filterIsInstance<AiStreamEvent.Failed>().isEmpty())
        assertEquals(2, recorder.events.size)
        assertEquals(false, recorder.events[0].success)
        assertEquals("RateLimited", recorder.events[0].errorKind)
        assertEquals(true, recorder.events[1].success)
    }

    @Test
    fun providerFailedEventIsRecordedWhenNotFallbackEligible() = runTest {
        val model = ScriptedStreamingModel("p1", "m1", listOf(
            scriptedFlow(AiStreamEvent.Failed(AiError.InvalidRequest("bad request")))
        ))
        val provider = ScriptedStreamingProvider("p1", model)
        val registry = DefaultProviderRegistry(mapOf("p1" to provider))
        val router = DefaultModelRouter(RoutingConfig(defaultModel = AiModelId("p1", "m1")))
        val recorder = CapturingRecorder()

        val result = collectLoop(
            request = StreamTextRequest(
                model = AiModelId("p1", "m1"),
                messages = listOf(AiMessage(AiRole.User, listOf(AiContent.Text("x")))),
            ),
            registry = registry,
            router = router,
            recorder = recorder,
        )

        assertEquals(1, result.filterIsInstance<AiStreamEvent.Failed>().size)
        assertTrue(result.filterIsInstance<AiStreamEvent.Started>().isEmpty())
        assertEquals(1, recorder.events.size)
        assertEquals(false, recorder.events[0].success)
        assertEquals("InvalidRequest", recorder.events[0].errorKind)
    }

    /**
     * Test 7: no fallback after first event — provider emits TextDelta then throws.
     * AiClient must emit Started + TextDelta + Failed (no switch to provider2).
     */
    @Test
    fun noFallbackAfterFirstEventEmitsFailed() = runTest {
        // Provider 1: emits one TextDelta, then throws Network error
        val model1 = ScriptedStreamingModel("p1", "m1", listOf(
            flow {
                emit(AiStreamEvent.TextDelta("partial"))
                throw AiException(AiError.Network("connection lost", null))
            }
        ))
        val model2 = ScriptedStreamingModel("p2", "m2", listOf(
            scriptedFlow(completedEvent("should not reach here"))
        ))
        val provider1 = ScriptedStreamingProvider("p1", model1)
        val provider2 = ScriptedStreamingProvider("p2", model2)
        val registry = DefaultProviderRegistry(mapOf("p1" to provider1, "p2" to provider2))
        val router = DefaultModelRouter(RoutingConfig(
            policies = mapOf("any" to ModelPolicy(
                prefer = listOf(AiModelId("p1", "m1")),
                fallback = listOf(AiModelId("p2", "m2")),
            ))
        ))

        val request = StreamTextRequest(
            modelPolicy = "any",
            messages = listOf(AiMessage(AiRole.User, listOf(AiContent.Text("x")))),
        )

        val result = collectLoop(request, registry, router)

        assertIs<AiStreamEvent.Started>(result[0])
        assertEquals("p1", (result[0] as AiStreamEvent.Started).providerId)
        assertIs<AiStreamEvent.TextDelta>(result[1])
        assertEquals("partial", (result[1] as AiStreamEvent.TextDelta).text)
        assertIs<AiStreamEvent.Failed>(result[2])
        assertIs<AiError.Network>((result[2] as AiStreamEvent.Failed).error)
        assertEquals(3, result.size, "No more events after Failed")
        // Provider2 must NOT have been called
        assertEquals(0, model2.calls, "Fallback provider should NOT be called after Started was emitted")
    }

    /**
     * Test 8: cancellation does not emit Failed — when the collector cancels mid-stream via take(N),
     * no Failed event is emitted. Only Started + TextDelta events are observed.
     *
     * Uses Flow.take() which performs downstream cancellation: the upstream channelFlow receives
     * CancellationException and must rethrow it rather than emitting Failed.
     */
    @Test
    fun cancellationDoesNotEmitFailed() = runTest {
        // Provider emits two TextDeltas then suspends forever — never emits Completed or Failed
        val neverEndingFlow = flow<AiStreamEvent> {
            emit(AiStreamEvent.TextDelta("first"))
            emit(AiStreamEvent.TextDelta("second"))
            kotlinx.coroutines.suspendCancellableCoroutine<Unit> { /* never resume */ }
        }
        val model = ScriptedStreamingModel("p1", "m1", listOf(neverEndingFlow))
        val provider = ScriptedStreamingProvider("p1", model)
        val registry = DefaultProviderRegistry(mapOf("p1" to provider))
        val router = DefaultModelRouter(RoutingConfig(defaultModel = AiModelId("p1", "m1")))

        val request = StreamTextRequest(
            model = AiModelId("p1", "m1"),
            messages = listOf(AiMessage(AiRole.User, listOf(AiContent.Text("x")))),
        )

        // take(2) cancels the upstream after 2 events: Started + TextDelta("first")
        // If StreamingToolLoop incorrectly catches CancellationException and emits Failed,
        // the take() limit would be exceeded. Instead no Failed is emitted.
        val collected = runStreamingToolLoop(request, registry, router, CapturingRecorder())
            .take(2)
            .toList()

        // Verify we got exactly Started + TextDelta("first"), no Failed
        assertEquals(2, collected.size, "take(2) should yield exactly 2 events")
        assertIs<AiStreamEvent.Started>(collected[0])
        assertIs<AiStreamEvent.TextDelta>(collected[1])
        assertEquals("first", (collected[1] as AiStreamEvent.TextDelta).text)
        val failedEvents = collected.filterIsInstance<AiStreamEvent.Failed>()
        assertTrue(failedEvents.isEmpty(), "Cancellation MUST NOT emit a Failed event; got: $failedEvents")
    }
}
