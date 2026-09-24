package neton.redis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class RespReplyTest {

    private fun bulk(value: String): ByteArray {
        val payload = value.encodeToByteArray()
        return "$${payload.size}\r\n".encodeToByteArray() + payload + "\r\n".encodeToByteArray()
    }

    @Test
    fun asciiBulkString() {
        assertEquals("hello", RespReply.bulkString(bulk("hello")))
    }

    /**
     * The RESP length prefix counts bytes. Slicing decoded characters with it runs past the end
     * of any value holding multi-byte UTF-8 — a queued batch with one Chinese message was enough
     * to crash the consumer on every restart.
     */
    @Test
    fun multiByteBulkStringUsesByteLength() {
        val value = """{"message":"主线程 1200 ms 未响应（仍未恢复）","emoji":"🐶"}"""
        assertEquals(value, RespReply.bulkString(bulk(value)))
    }

    @Test
    fun nullBulkString() {
        assertNull(RespReply.bulkString("$-1\r\n".encodeToByteArray()))
    }

    @Test
    fun emptyReply() {
        assertNull(RespReply.bulkString(ByteArray(0)))
    }

    @Test
    fun simpleString() {
        assertEquals("OK", RespReply.bulkString("+OK\r\n".encodeToByteArray()))
    }

    /** An error reply is a failure, not a value: returning "-WRONGTYPE ..." as a list element
     * would hand the caller garbage that looks like data. */
    @Test
    fun errorReplyThrows() {
        val e = assertFailsWith<RedisException> {
            RespReply.bulkString("-WRONGTYPE Operation against a key holding the wrong kind of value\r\n".encodeToByteArray())
        }
        assertEquals("WRONGTYPE Operation against a key holding the wrong kind of value", e.message)
    }

    @Test
    fun truncatedBulkStringThrowsRedisException() {
        assertFailsWith<RedisException> { RespReply.bulkString("$10\r\nabc".encodeToByteArray()) }
    }
}
