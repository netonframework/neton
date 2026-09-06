package neton.http.adapter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The header block must stay unparsed while only single lookups happen — including
 * lookups that find nothing, which is the common case for the header dispatch reads
 * on the way in.
 */
class LazyRequestHeadersTest {

    private class Counting(private val map: Map<String, List<String>>) {
        var calls = 0
            private set
        fun provide(): Map<String, List<String>> { calls++; return map }
        fun single(name: String): String? =
            map.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
    }

    private fun request(c: Counting) = BufferedHttpRequest(
        "GET", "/", "", ByteArray(0), "", c::single, c::provide,
    )

    @Test
    fun aPresentHeaderIsAnsweredWithoutBuildingTheMap() {
        val c = Counting(mapOf("X-Request-Id" to listOf("abc")))
        assertEquals("abc", request(c).header("x-request-id"))
        assertEquals(0, c.calls, "the map must not be built for a single lookup")
    }

    @Test
    fun anAbsentHeaderAlsoAnswersWithoutBuildingTheMap() {
        val c = Counting(mapOf("Host" to listOf("example.com")))
        assertNull(request(c).header("X-Request-Id"))
        assertEquals(0, c.calls, "a lookup that finds nothing must not fall back to the map")
    }

    @Test
    fun askingForTheWholeMapBuildsItExactlyOnce() {
        val c = Counting(mapOf("Host" to listOf("example.com")))
        val r = request(c)
        assertEquals(1, r.headers.size)
        assertEquals(1, r.headers.size)
        assertEquals(1, c.calls, "the map is memoised")
    }

    @Test
    fun withoutAFastPathLookupsGoThroughTheMap() {
        val r = BufferedHttpRequest("GET", "/", "", mapOf("Host" to listOf("h")), ByteArray(0))
        assertEquals("h", r.header("host"))
        assertNull(r.header("X-Request-Id"))
    }
}
