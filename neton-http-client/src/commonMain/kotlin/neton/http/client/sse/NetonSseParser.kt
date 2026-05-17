// neton-http-client/src/commonMain/kotlin/neton/http/client/sse/NetonSseParser.kt
package neton.http.client.sse

/**
 * Line-by-line SSE parser per W3C / WHATWG Server-Sent Events spec.
 * Stateful: feed lines via [accept]; events are emitted when a blank line is encountered (per spec).
 * Call [finish] at end of stream to flush any pending event without trailing blank line.
 *
 * Not thread-safe. Use one parser per stream / collection coroutine.
 */
class NetonSseParser {

    private var dataBuffer = StringBuilder()
    private var eventType: String? = null
    private var lastId: String? = null
    private var hasData = false

    /**
     * Feed one line (already stripped of CR/LF terminator).
     * Returns 0 or 1 emitted events.
     */
    fun accept(line: String): List<NetonSseEvent> {
        // Blank line: dispatch event if data accumulated
        if (line.isEmpty()) return dispatch()

        // Comment line: ignore
        if (line.startsWith(":")) return emptyList()

        // Field parse: "name:value" or "name:" or "name" (no colon = empty value)
        val colonIdx = line.indexOf(':')
        val field: String
        val rawValue: String
        if (colonIdx < 0) {
            field = line
            rawValue = ""
        } else {
            field = line.substring(0, colonIdx)
            // Per spec: strip single leading space from value
            rawValue = line.substring(colonIdx + 1).let { if (it.startsWith(" ")) it.drop(1) else it }
        }

        when (field) {
            "data" -> {
                if (hasData) dataBuffer.append('\n')
                dataBuffer.append(rawValue)
                hasData = true
            }
            "event" -> eventType = rawValue
            "id" -> {
                // Spec: ignore id values containing NUL; we use isNotBlank() as a simple guard
                if (rawValue.isNotEmpty() && !rawValue.contains(' ')) lastId = rawValue
            }
            // "retry" and unknown fields: ignored (not represented in NetonSseEvent)
        }

        return emptyList()
    }

    /**
     * Flush any pending event (e.g., stream ended without trailing blank line).
     */
    fun finish(): List<NetonSseEvent> = dispatch()

    private fun dispatch(): List<NetonSseEvent> {
        if (!hasData) {
            // Per spec: do not dispatch if data buffer is empty (no "data:" field seen since last event)
            // Reset eventType anyway (spec: event field reset after each dispatch / blank line)
            eventType = null
            return emptyList()
        }
        val event = NetonSseEvent(
            id = lastId,  // lastId persists across events per spec
            event = eventType,
            data = dataBuffer.toString(),
        )
        dataBuffer = StringBuilder()
        eventType = null
        hasData = false
        return listOf(event)
    }
}
