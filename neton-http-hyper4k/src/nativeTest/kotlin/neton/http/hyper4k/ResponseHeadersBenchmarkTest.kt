package neton.http.hyper4k

import hyper4k.Hyper4kResponse
import hyper4k.Hyper4kResponseChannel
import kotlinx.coroutines.runBlocking
import neton.core.config.getEnv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.TimeSource

/** Opt-in snapshot microbenchmark, not an HTTP throughput or release gate. */
class ResponseHeadersBenchmarkTest {
    @Test
    fun compareHeaderSnapshots() = runBlocking {
        if (getEnv("NETON_HEADER_BENCH") != "1") return@runBlocking
        val channel = object : Hyper4kResponseChannel {
            override val isStreaming = false
            override val bytesWritten = 0L
            override suspend fun begin(status: Int, headers: Map<String, List<String>>) = error("buffered only")
            override suspend fun write(chunk: ByteArray): Boolean = error("buffered only")
            override suspend fun finish() = error("buffered only")
        }
        val body = byteArrayOf(55)
        val response = Hyper4kLiveResponse(channel, emptyMap())
        response.contentType = "text/plain"
        response.write(body)

        // Keep the previous production snapshot algorithm as the control.
        fun original(): Hyper4kResponse {
            val headers = buildMap<String, MutableList<String>> {
                for (name in response.headers.names()) {
                    if (name.equals("Content-Length", ignoreCase = true)) continue
                    getOrPut(name) { mutableListOf() }.addAll(response.headers.getAll(name))
                }
                if (none { it.key.equals("Content-Type", ignoreCase = true) }) {
                    response.contentType?.let { put("Content-Type", mutableListOf(it)) }
                }
            }
            return Hyper4kResponse(response.status.code, headers, body)
        }
        assertEquals(original().headers, response.completeResponse().headers)
        val iterations = 200_000
        fun measure(optimized: Boolean): Long {
            var checksum = 0L
            val start = TimeSource.Monotonic.markNow()
            repeat(iterations) {
                val result = if (optimized) response.completeResponse() else original()
                checksum += result.headers["Content-Type"]!!.first().length + result.body.size
            }
            val elapsed = start.elapsedNow().inWholeNanoseconds
            assertEquals(iterations * 11L, checksum)
            return elapsed / iterations
        }
        measure(false)
        measure(true)
        repeat(6) { round ->
            val order = if (round % 2 == 0) listOf(false, true) else listOf(true, false)
            for (optimized in order) {
                println("header-snapshot round=$round optimized=$optimized ns/op=${measure(optimized)}")
            }
        }
    }
}
