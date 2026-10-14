package neton.ai.protocol.realtime

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import neton.ai.protocol.obj
import neton.ai.protocol.usage.Usage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RealtimeBridgeTest {
    /** 内存通道：inbox 是对端发来的，outbox 是发往对端的。 */
    private class Pipe : RealtimeChannel {
        val inbox = Channel<String>(Channel.UNLIMITED)
        val outbox = Channel<String>(Channel.UNLIMITED)
        var closed = false
        override suspend fun receive(): String? = inbox.receiveCatching().getOrNull()
        override suspend fun send(text: String) { outbox.send(text) }
        override suspend fun close(code: Int, reason: String) { closed = true; inbox.close(); outbox.close() }
    }

    @Test
    fun relaysRewritesRejectsAndMeters() = runTest {
        val client = Pipe()
        val upstream = Pipe()
        val usages = mutableListOf<Usage>()
        val bridge = RealtimeBridge(client, upstream,
            onClientEvent = { e ->
                if ((e["type"] as? JsonPrimitive)?.contentOrNull == "conversation.item.create" && "forbidden" in e.toString())
                    ClientEventDecision.Reject("not_allowed", "this item is not allowed")
                else ClientEventDecision.Forward(RealtimeUsage.withModel(e, "gpt-realtime-mapped"))
            },
            onUsage = { usages += it })
        val job = launch { bridge.run() }

        client.inbox.send("""{"type":"session.update","session":{"model":"gpt-realtime","voice":"alloy"}}""")
        client.inbox.send("""{"type":"conversation.item.create","event_id":"e1","item":{"text":"forbidden"}}""")
        upstream.inbox.send("""{"type":"response.audio.delta","delta":"AAA"}""")
        upstream.inbox.send("""{"type":"response.done","response":{"usage":{"input_tokens":20,"output_tokens":8,"input_token_details":{"cached_tokens":5,"audio_tokens":12},"output_token_details":{"audio_tokens":6}}}}""")

        val forwarded = obj(upstream.outbox.receive())
        assertEquals("gpt-realtime-mapped", forwarded["session"]!!.jsonObject["model"]!!.let { (it as JsonPrimitive).content })
        // 两个方向并发转发，到达客户端的顺序不固定：按类型核对
        val toClient = List(3) { obj(client.outbox.receive()) }.associateBy { (it["type"] as JsonPrimitive).content }
        assertEquals(setOf("error", "response.audio.delta", "response.done"), toClient.keys)
        assertEquals("not_allowed", toClient["error"]!!["error"]!!.jsonObject["code"]!!.let { (it as JsonPrimitive).content })
        assertEquals(1, usages.size)
        assertEquals(12L, usages[0].audioInputTokens)
        assertEquals(5L, usages[0].cacheReadTokens)

        // 上游关闭 → 客户端也被关闭
        upstream.inbox.close()
        job.join()
        assertTrue(client.closed)
    }

    @Test
    fun responsesWebSocketUsage() {
        val u = RealtimeUsage.fromEvent(obj("""{"type":"response.completed","response":{"usage":{"input_tokens":3,"output_tokens":4,"output_tokens_details":{"reasoning_tokens":2}}}}"""))!!
        assertEquals(2L, u.reasoningTokens)
        val e = RealtimeUsage.withModel(obj("""{"type":"response.create","model":"gpt-5","input":"hi"}"""), "mapped")
        assertEquals("mapped", (e["model"] as JsonPrimitive).content)
    }
}
