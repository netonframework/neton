package neton.http.client

import neton.core.http.HttpHeaders

sealed interface HttpClientStreamChunk {
    data class Bytes(val bytes: ByteArray) : HttpClientStreamChunk {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Bytes) return false
            return bytes.contentEquals(other.bytes)
        }
        override fun hashCode(): Int = bytes.contentHashCode()
    }

    data class Text(val text: String) : HttpClientStreamChunk

    /** Terminal chunk; emitted after the last byte/text chunk to signal end of body. */
    data class End(val finalHeaders: HttpHeaders) : HttpClientStreamChunk
}
