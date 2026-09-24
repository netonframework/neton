package neton.redis

/**
 * Decoding of raw RESP replies for the commands this client issues through `execute` rather than
 * a typed ReThis command.
 *
 * Works on bytes, not on a decoded string. The bulk-string length prefix counts **bytes**; using it
 * to slice decoded characters is only correct while every byte is one character. The first value
 * holding multi-byte UTF-8 (a Chinese message, an emoji) makes the slice run past the end, and on a
 * queue consumer that means the same element crashes the process on every restart.
 */
internal object RespReply {

    private const val CR: Byte = '\r'.code.toByte()
    private const val LF: Byte = '\n'.code.toByte()

    /**
     * A bulk string (`$<len>\r\n<data>\r\n`), a null bulk string (`$-1`, returned as null) or a
     * simple string (`+<data>\r\n`). An error reply (`-<message>`) throws: handing it back as a
     * value would give the caller "WRONGTYPE ..." as if it were stored data.
     */
    fun bulkString(bytes: ByteArray): String? {
        if (bytes.isEmpty()) return null
        val headerEnd = indexOfCrlf(bytes)
        val header = if (headerEnd < 0) bytes.decodeToString(1) else bytes.decodeToString(1, headerEnd)
        return when (bytes[0]) {
            '$'.code.toByte() -> {
                if (headerEnd < 0) throw RedisException("malformed bulk reply: missing CRLF after length")
                val len = header.toIntOrNull()
                    ?: throw RedisException("malformed bulk reply: length '$header' is not a number")
                if (len < 0) return null
                val start = headerEnd + 2
                if (start + len > bytes.size) {
                    throw RedisException(
                        "truncated bulk reply: expected $len bytes, got ${bytes.size - start}",
                    )
                }
                bytes.decodeToString(start, start + len)
            }
            '+'.code.toByte() -> header
            '-'.code.toByte() -> throw RedisException(header)
            else -> bytes.decodeToString().trimEnd('\r', '\n')
        }
    }

    private fun indexOfCrlf(bytes: ByteArray): Int {
        for (i in 0 until bytes.size - 1) {
            if (bytes[i] == CR && bytes[i + 1] == LF) return i
        }
        return -1
    }
}
