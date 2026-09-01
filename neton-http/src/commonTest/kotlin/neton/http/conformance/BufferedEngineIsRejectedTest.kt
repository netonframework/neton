package neton.http.conformance

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import neton.core.component.NetonContext
import neton.core.http.HttpBodyWriter
import neton.core.http.adapter.HttpAdapter
import neton.core.http.adapter.HttpCapability
import kotlin.test.Test
import kotlin.test.assertFails

/**
 * The suite's own negative gate.
 *
 * `checkStreamingReleasesChunksAsProduced` is only worth anything if a buffering
 * engine cannot pass it. Proving that by editing a real adapter and watching the
 * build go red is a one-off; this pins it down permanently, so the check cannot
 * decay into something every implementation satisfies.
 */
class BufferedEngineIsRejectedTest {

    @Test
    fun bufferingEngineFailsTheStreamingCheck() = runTest {
        assertFails {
            BufferedSuite().checkStreamingReleasesChunksAsProduced()
        }
    }

    /** An engine that claims streaming but writes nothing until the producer returns. */
    private class BufferedSuite : HttpEngineConformanceSuite() {

        override fun createAdapter(): HttpAdapter = object : HttpAdapter {
            override val capabilities = setOf(HttpCapability.STREAMING_RESPONSE)
            override fun port(): Int = 0
            override fun adapterName(): String = "buffered-fake"
            override suspend fun start(ctx: NetonContext, onStarted: (suspend (Long) -> Unit)?) = Unit
            override suspend fun stop() = Unit
        }

        override suspend fun roundTrip(request: ConformanceRequest): ConformanceResponse =
            throw UnsupportedOperationException("not part of this test")

        override fun recordSkipped(capability: HttpCapability, testName: String) =
            throw AssertionError("the fake declares $capability, so $testName must not be skipped")

        override suspend fun streamRoundTrip(
            request: ConformanceRequest,
            produce: suspend (writer: HttpBodyWriter, meter: ChunkMeter) -> Unit,
        ): ConformanceStream {
            val collected = mutableListOf<ByteArray>()
            val writer = object : HttpBodyWriter {
                override suspend fun writeChunk(chunk: ByteArray) { collected += chunk }
            }
            // Nothing is delivered while the producer runs, which is exactly what a
            // buffering transport does, so the meter never advances.
            val meter = object : ChunkMeter {
                override fun released(): Int = 0
                override suspend fun awaitReleased(count: Int, timeoutMillis: Long): Boolean =
                    withTimeoutOrNull(timeoutMillis) {
                        while (true) delay(2)
                        @Suppress("UNREACHABLE_CODE") true
                    } ?: false
            }
            produce(writer, meter)
            return ConformanceStream(status = 200, chunks = collected)
        }
    }
}
