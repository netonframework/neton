package neton.http.netonstream

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import neton.http.Body
import neton.http.Frame
import neton.http.header.HeaderMap
import neton.io.bytes.Bytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class RequestBodyBufferTest {
    private class Frames(
        private val frames: List<Frame>,
        override val exactLength: Long = -1,
    ) : Body {
        var reads = 0
        override suspend fun nextFrame(): Frame? = frames.getOrNull(reads++)
    }

    private fun data(vararg bytes: Byte) = Frame.Data(Bytes.wrap(bytes))

    @Test
    fun smallUnknownBodyUsesItsFirstDataSizeNotAKilobyte() = runBlocking {
        val capacities = mutableListOf<Int>()
        val input = byteArrayOf(1, 2, 3)
        val body = Frames(listOf(Frame.Data(Bytes.wrap(input)), Frame.Trailers(HeaderMap())))
        val result = readBody(body, 2048, capacities::add)
        assertContentEquals(input, result)
        assertEquals(listOf(3), capacities)
        assertEquals(3, body.reads, "trailers and end-of-stream must still be consumed")
        input[0] = 99
        assertContentEquals(byteArrayOf(1, 2, 3), result, "the application owns a copy")
    }

    @Test
    fun emptyFramesAndTrailersDoNotReserveABuffer() = runBlocking {
        val capacities = mutableListOf<Int>()
        val result = readBody(Frames(listOf(data(), Frame.Trailers(HeaderMap()))), 16, capacities::add)
        assertContentEquals(byteArrayOf(), result)
        assertEquals(emptyList(), capacities)
    }

    @Test
    fun fragmentedBodyGrowsGeometricallyAfterTheFirstChunk() = runBlocking {
        val capacities = mutableListOf<Int>()
        val result = readBody(Frames(listOf(data(1, 2), data(), data(3), data(4, 5))), 2048, capacities::add)
        assertContentEquals(byteArrayOf(1, 2, 3, 4, 5), result)
        assertEquals(listOf(2, 1024), capacities)
    }

    @Test
    fun knownLengthStillAllocatesOnceAndReadsToEnd() = runBlocking {
        val capacities = mutableListOf<Int>()
        val body = Frames(listOf(data(1, 2), data(3), Frame.Trailers(HeaderMap())), 3)
        assertContentEquals(byteArrayOf(1, 2, 3), readBody(body, 16, capacities::add))
        assertEquals(listOf(3), capacities)
        assertEquals(4, body.reads)
    }

    @Test
    fun declaredLengthIsNotUsedToDiscardAdditionalFrames() = runBlocking {
        val capacities = mutableListOf<Int>()
        val body = Frames(listOf(data(1, 2), data(3, 4)), 2)
        assertContentEquals(byteArrayOf(1, 2, 3, 4), readBody(body, 4, capacities::add))
        assertEquals(listOf(2, 4), capacities)
    }

    @Test
    fun bodyLimitsRejectBeforeAnOversizedAllocation() = runBlocking {
        val capacities = mutableListOf<Int>()
        val declared = Frames(listOf(data(1)), 17)
        assertNull(readBody(declared, 16, capacities::add))
        assertEquals(0, declared.reads)
        assertEquals(emptyList(), capacities)
        assertNull(readBody(Frames(listOf(data(1, 2, 3))), 2, capacities::add))
        assertEquals(emptyList(), capacities)
        assertNull(readBody(Frames(listOf(data(1, 2), data(3))), 2, capacities::add))
        assertEquals(listOf(2), capacities)
    }

    @Test
    fun reservationFailureStopsReadingImmediately() = runBlocking {
        val body = Frames(listOf(data(1, 2), data(3)))
        assertFailsWith<IllegalStateException> {
            readBody(body, 16) { error("budget exhausted") }
        }
        assertEquals(1, body.reads)
    }

    @Test
    fun cancellationAndLateErrorsAreNotTurnedIntoPartialSuccess() = runBlocking {
        for (error in listOf(CancellationException("cancelled"), IllegalStateException("broken body"))) {
            var first = true
            val body = object : Body {
                override suspend fun nextFrame(): Frame? {
                    delay(1)
                    if (first) {
                        first = false
                        return data(1, 2)
                    }
                    throw error
                }
            }
            val thrown = assertFailsWith<Exception> { readBody(body, 16) { } }
            assertEquals(error, thrown)
        }
    }
}
