package neton.http.client

sealed interface NetonHttpBody {
    /** Pre-serialized JSON string; Content-Type set to application/json. */
    data class Json(val text: String) : NetonHttpBody

    /** Arbitrary text with caller-supplied content type. */
    data class Text(val text: String, val contentType: String) : NetonHttpBody

    /** Arbitrary bytes with caller-supplied content type. */
    data class Bytes(val bytes: ByteArray, val contentType: String) : NetonHttpBody {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Bytes) return false
            return bytes.contentEquals(other.bytes) && contentType == other.contentType
        }
        override fun hashCode(): Int = 31 * bytes.contentHashCode() + contentType.hashCode()
    }
}
