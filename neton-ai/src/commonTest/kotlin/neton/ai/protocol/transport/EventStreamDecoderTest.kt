package neton.ai.protocol.transport

import neton.ai.protocol.transport.aws.EventStreamDecoder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EventStreamDecoderTest {
    private fun headersBytes(headers: Map<String, String>): ByteArray {
        val out = ArrayList<Byte>()
        for ((k, v) in headers) {
            val kb = k.encodeToByteArray(); val vb = v.encodeToByteArray()
            out += kb.size.toByte(); kb.forEach { out += it }; out += 7.toByte(); out += (vb.size ushr 8).toByte(); out += vb.size.toByte(); vb.forEach { out += it }
        }
        return out.toByteArray()
    }
    private fun frame(headers: Map<String, String>, payload: String): ByteArray {
        val h = headersBytes(headers); val p = payload.encodeToByteArray()
        val total = 12 + h.size + p.size + 4
        val out = ByteArray(total)
        fun putInt(o: Int, v: Int) { out[o] = (v ushr 24).toByte(); out[o + 1] = (v ushr 16).toByte(); out[o + 2] = (v ushr 8).toByte(); out[o + 3] = v.toByte() }
        putInt(0, total); putInt(4, h.size); putInt(8, 0)
        h.copyInto(out, 12); p.copyInto(out, 12 + h.size); putInt(total - 4, 0)
        return out
    }

    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    @Test
    fun decodesChunkFramesAcrossSplitsAndExceptions() {
        val ev1 = """{"type":"message_start","message":{"usage":{"input_tokens":3}}}"""
        val ev2 = """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"hi"}}"""
        val f1 = frame(mapOf(":message-type" to "event", ":event-type" to "chunk"), """{"bytes":"${kotlin.io.encoding.Base64.encode(ev1.encodeToByteArray())}"}""")
        val f2 = frame(mapOf(":message-type" to "event", ":event-type" to "chunk"), """{"bytes":"${kotlin.io.encoding.Base64.encode(ev2.encodeToByteArray())}"}""")
        val f3 = frame(mapOf(":message-type" to "exception", ":exception-type" to "throttlingException"), """{"message":"slow down"}""")
        val all = f1 + f2 + f3
        val d = EventStreamDecoder()
        val out = StringBuilder()
        out.append(d.feed(all.copyOfRange(0, 7)))
        out.append(d.feed(all.copyOfRange(7, f1.size + 20)))
        out.append(d.feed(all.copyOfRange(f1.size + 20, all.size)))
        out.append(d.finish())
        val t = out.toString()
        assertEquals(listOf("message_start", "content_block_delta", "error"), Regex("event: (\\w+)").findAll(t).map { it.groupValues[1] }.toList())
        assertTrue(t.contains("data: $ev2"))
        assertTrue(t.contains("\"type\":\"throttlingException\",\"message\":\"slow down\""))
    }
}

class EventStreamDecoderBufferTest {
    @Test
    fun manyFramesAcrossManyTinyChunksDoNotCorrupt() {
        // 200 帧、每次只喂 5 字节：验证增长缓冲 + 偏移压缩不会丢帧或错位
        val d = EventStreamDecoder()
        val frames = (1..200).map { i ->
            val h = headersBytesFor(mapOf(":message-type" to "event"))
            val p = """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"$i"}}""".encodeToByteArray()
            val total = 12 + h.size + p.size + 4
            ByteArray(total).also { out ->
                fun putInt(o: Int, v: Int) { out[o] = (v ushr 24).toByte(); out[o + 1] = (v ushr 16).toByte(); out[o + 2] = (v ushr 8).toByte(); out[o + 3] = v.toByte() }
                putInt(0, total); putInt(4, h.size); h.copyInto(out, 12); p.copyInto(out, 12 + h.size)
            }
        }
        val all = frames.reduce { a, b -> a + b }
        val out = StringBuilder()
        var i = 0
        while (i < all.size) { out.append(d.feed(all.copyOfRange(i, minOf(i + 5, all.size)))); i += 5 }
        val texts = Regex("\"text\":\"(\\d+)\"").findAll(out).map { it.groupValues[1].toInt() }.toList()
        assertEquals((1..200).toList(), texts)
    }

    private fun headersBytesFor(headers: Map<String, String>): ByteArray {
        val out = ArrayList<Byte>()
        for ((k, v) in headers) {
            val kb = k.encodeToByteArray(); val vb = v.encodeToByteArray()
            out += kb.size.toByte(); kb.forEach { out += it }; out += 7.toByte(); out += (vb.size ushr 8).toByte(); out += vb.size.toByte(); vb.forEach { out += it }
        }
        return out.toByteArray()
    }
}
