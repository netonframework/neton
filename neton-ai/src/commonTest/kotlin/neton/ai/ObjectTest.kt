package neton.ai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import neton.ai.protocol.ir.ResponseFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ObjectTest {
    @Serializable data class City(val name: String, val population: Int)

    /** 只实现 generateText / streamText 的替身客户端，记录收到的请求。 */
    private class FakeClient(val message: AiMessage, val finish: AiFinishReason = AiFinishReason.Stop, val deltas: List<String> = emptyList()) : AiClient {
        var lastFormat: ResponseFormat? = null
        override suspend fun generateText(block: neton.ai.builder.GenerateTextRequestBuilder.() -> Unit) = error("unused")
        override suspend fun generateText(request: GenerateTextRequest): GenerateTextResult {
            lastFormat = request.responseFormat
            return GenerateTextResult(message.text, message, emptyList(), emptyList(), null, finish, "p", "m", 1)
        }
        override fun streamText(block: neton.ai.builder.StreamTextRequestBuilder.() -> Unit) = error("unused")
        override fun streamText(request: StreamTextRequest): Flow<AiStreamEvent> = flow {
            lastFormat = request.responseFormat
            deltas.forEach { emit(AiStreamEvent.TextDelta(it)) }
            emit(AiStreamEvent.Completed(message, message.text, null, finish))
        }
        override suspend fun embed(block: neton.ai.builder.EmbeddingRequestBuilder.() -> Unit) = error("unused")
        override suspend fun embed(request: EmbeddingRequest) = error("unused")
        override suspend fun close() {}
    }

    private val req = GenerateTextRequest(messages = listOf(AiMessage.user("a city")))

    @Test
    fun successDecodesAndSendsStrictSchema() = runTest {
        val client = FakeClient(AiMessage.assistant("""{"name":"Oslo","population":700000}"""))
        val r = client.generateObject<City>(req)
        assertEquals(City("Oslo", 700000), (r as AiObjectResult.Success).value)
        val format = client.lastFormat as ResponseFormat.JsonSchema
        assertEquals(true, format.strict)
        assertEquals(JsonPrimitive(false), format.schema.jsonObject["additionalProperties"])
    }

    @Test
    fun failuresAreDistinguished() = runTest {
        assertIs<AiObjectResult.Refused>(FakeClient(AiMessage(AiRole.Assistant, listOf(AiContent.Refusal("no")))).generateObject<City>(req))
        assertIs<AiObjectResult.Truncated>(FakeClient(AiMessage.assistant("""{"name":"Os"""), AiFinishReason.Length).generateObject<City>(req))
        assertIs<AiObjectResult.Invalid>(FakeClient(AiMessage.assistant("not json")).generateObject<City>(req))
        assertIs<AiObjectResult.Invalid>(FakeClient(AiMessage.assistant("""{"name":"Oslo"}""")).generateObject<City>(req))
        // 只去掉一层代码块
        assertIs<AiObjectResult.Success<City>>(FakeClient(AiMessage.assistant("```json\n{\"name\":\"A\",\"population\":1}\n```")).generateObject<City>(req))
    }

    @Test
    fun streamingPartialsAreNeverTheResult() = runTest {
        val full = """{"name":"Oslo","population":700000}"""
        val client = FakeClient(AiMessage.assistant(full), deltas = listOf("""{"name":"Os""", """lo","popu""", """lation":700000}"""))
        val events = client.streamObject(kotlinx.serialization.serializer<City>(), StreamTextRequest(messages = listOf(AiMessage.user("x")))).toList()
        val partials = events.filterIsInstance<AiObjectStreamEvent.Partial>()
        assertEquals(3, partials.size)
        assertEquals(JsonPrimitive("Os"), partials[0].partial!!.jsonObject["name"])
        assertTrue(events.last() is AiObjectStreamEvent.Final)
        assertEquals(1, events.count { it is AiObjectStreamEvent.Final })
        assertIs<AiObjectResult.Success<City>>((events.last() as AiObjectStreamEvent.Final).result)

        // 被截断的流：半成品可以解析，但最终结果是 Truncated
        val cut = FakeClient(AiMessage.assistant("""{"name":"Os"""), AiFinishReason.Length, deltas = listOf("""{"name":"Os"""))
        val last = cut.streamObject(kotlinx.serialization.serializer<City>(), StreamTextRequest(messages = listOf(AiMessage.user("x")))).toList().last()
        assertIs<AiObjectResult.Truncated>((last as AiObjectStreamEvent.Final).result)
    }
}
