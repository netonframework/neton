package neton.core.http

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** 测试用最简 MutableHeaders。 */
internal class TestHeaders : MutableHeaders {
    private val map = LinkedHashMap<String, MutableList<String>>()
    override fun get(name: String): String? = map[name]?.firstOrNull()
    override fun getAll(name: String): List<String> = map[name] ?: emptyList()
    override fun contains(name: String): Boolean = map.containsKey(name)
    override fun names(): Set<String> = map.keys
    override fun toMap(): Map<String, List<String>> = map
    override fun set(name: String, value: String) { map[name] = mutableListOf(value) }
    override fun add(name: String, value: String) { map.getOrPut(name) { mutableListOf() }.add(value) }
    override fun remove(name: String) { map.remove(name) }
    override fun clear() { map.clear() }
}

/** 契约：默认 stream() 把多次 writeChunk 缓冲为单次 write，块序保持（不支持真流式的适配器兼容路径）。 */
class HttpResponseStreamContractTest {

    private class RecordingResponse : HttpResponse {
        override var status: HttpStatus = HttpStatus.OK
        override val headers: MutableHeaders = TestHeaders()
        override val isCommitted: Boolean get() = writes.isNotEmpty()
        val writes = mutableListOf<ByteArray>()
        override fun cookie(cookie: Cookie) {}
        override suspend fun write(data: ByteArray) { writes.add(data) }
    }

    @Test
    fun defaultStreamBuffersChunksIntoSingleWrite() = runBlocking {
        val response = RecordingResponse()
        response.stream {
            writeChunk("hello ".encodeToByteArray())
            writeChunk("world")
        }
        assertEquals(1, response.writes.size)
        assertContentEquals("hello world".encodeToByteArray(), response.writes[0])
    }

    @Test
    fun emptyStreamStillCommitsEmptyBody() = runBlocking {
        val response = RecordingResponse()
        response.stream { }
        assertEquals(1, response.writes.size)
        assertEquals(0, response.writes[0].size)
    }
}
