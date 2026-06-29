package neton.http.client

sealed interface NetonHttpStreamChunk {
    data class Bytes(val bytes: ByteArray) : NetonHttpStreamChunk {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Bytes) return false
            return bytes.contentEquals(other.bytes)
        }
        override fun hashCode(): Int = bytes.contentHashCode()
    }

    data class Text(val text: String) : NetonHttpStreamChunk

    /** Terminal chunk; emitted after the last byte/text chunk to signal end of body. */
    data class End(val finalHeaders: Map<String, String>) : NetonHttpStreamChunk
}
