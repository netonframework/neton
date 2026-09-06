package neton.http.adapter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * The decoder has a fast path for values with nothing to decode, which is the
 * shape almost every request has. These pin the two things that path must not
 * change: the result, and that it is the *same* string rather than a copy.
 */
class PercentDecodeTest {

    private fun decode(s: String) = BufferedHttpDispatcher.percentDecode(s)

    @Test
    fun leavesAPlainValueUntouchedAndAllocatesNothing() {
        val input = "Alpha-Widget_42.v1~x"
        assertSame(input, decode(input), "a value with nothing to decode must be returned as-is")
        assertSame("", decode(""))
    }

    @Test
    fun decodesPercentEscapes() {
        assertEquals("a b", decode("a%20b"))
        assertEquals("/", decode("%2F"))
        assertEquals("ä", decode("%C3%A4"))
        assertEquals("100%", decode("100%"))
    }

    @Test
    fun plusIsASpace() {
        assertEquals("a b c", decode("a+b+c"))
        assertEquals(" ", decode("+"))
    }

    @Test
    fun mixesEscapesAndPlainRuns() {
        assertEquals("name=Alpha Widget/x", decode("name%3DAlpha+Widget%2Fx"))
    }

    @Test
    fun aTruncatedOrInvalidEscapeIsKeptVerbatim() {
        // Not enough characters left for two hex digits, or not hex at all.
        assertEquals("%2", decode("%2"))
        assertEquals("%zz", decode("%zz"))
        assertEquals("a%", decode("a%"))
    }

    @Test
    fun multiByteUtf8SurvivesAByteWiseDecoder() {
        // The decoder works on bytes; a character that spans three of them must
        // still come back whole.
        assertEquals("中文", decode("%E4%B8%AD%E6%96%87"))
        assertEquals("中 文", decode("%E4%B8%AD+%E6%96%87"))
    }
}
