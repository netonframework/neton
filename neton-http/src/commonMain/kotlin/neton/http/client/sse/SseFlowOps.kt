package neton.http.client.sse

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import neton.http.client.NetonHttpStreamChunk

/**
 * Convert a Flow of complete lines (no terminators) into SSE events.
 * Each line is fed directly to a [NetonSseParser]; blank string ("") finalizes the current event.
 */
fun Flow<String>.parseSseEvents(): Flow<NetonSseEvent> = flow {
    val parser = NetonSseParser()
    collect { line ->
        parser.accept(line).forEach { emit(it) }
    }
    parser.finish().forEach { emit(it) }
}

/**
 * Convert a Flow of byte/text chunks (with arbitrary boundaries) into SSE events.
 * Handles cross-chunk line fragmentation by accumulating into a buffer and splitting on LF.
 * Strips trailing CR (handles CRLF line endings).
 * Stops accumulating when [NetonHttpStreamChunk.End] is received; flushes any pending event.
 */
fun Flow<NetonHttpStreamChunk>.parseSseEvents(): Flow<NetonSseEvent> = flow {
    val parser = NetonSseParser()
    // Use a mutable reference to a String so we can reassign the remainder after draining lines.
    var buffer = ""

    /**
     * Splits [buffer] on LF boundaries, feeds each complete line to [parser], emits events,
     * and updates [buffer] to hold only the incomplete trailing fragment (if any).
     */
    suspend fun drainLines() {
        var start = 0
        val s = buffer
        while (true) {
            val lf = s.indexOf('\n', start)
            if (lf < 0) break
            // Strip trailing CR for CRLF support
            val lineEnd = if (lf > start && s[lf - 1] == '\r') lf - 1 else lf
            val line = s.substring(start, lineEnd)
            parser.accept(line).forEach { emit(it) }
            start = lf + 1
        }
        // Keep only the unfinished fragment after the last LF
        buffer = if (start > 0) s.substring(start) else s
    }

    collect { chunk ->
        when (chunk) {
            is NetonHttpStreamChunk.Bytes -> {
                buffer += chunk.bytes.decodeToString()
                drainLines()
            }
            is NetonHttpStreamChunk.Text -> {
                buffer += chunk.text
                drainLines()
            }
            is NetonHttpStreamChunk.End -> {
                // Drain any complete lines remaining in the buffer
                drainLines()
                // If there is still an incomplete fragment without a trailing LF, feed it as a line
                if (buffer.isNotEmpty()) {
                    parser.accept(buffer).forEach { emit(it) }
                    buffer = ""
                }
                parser.finish().forEach { emit(it) }
                return@collect
            }
        }
    }
    // If End never arrived (Flow completed normally), flush remaining
    if (buffer.isNotEmpty()) {
        parser.accept(buffer).forEach { emit(it) }
    }
    parser.finish().forEach { emit(it) }
}
