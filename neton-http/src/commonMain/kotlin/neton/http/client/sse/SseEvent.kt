package neton.http.client.sse

/**
 * Server-Sent Event (SSE) per W3C / WHATWG spec.
 *
 * @property id   "id:" field (optional, used for resume after disconnect)
 * @property event "event:" field (optional, named events; Anthropic uses this — "message_start", "content_block_delta", etc.)
 * @property data "data:" field, concatenated by newlines if multi-line in source
 */
data class SseEvent(
    val id: String? = null,
    val event: String? = null,
    val data: String,
)
