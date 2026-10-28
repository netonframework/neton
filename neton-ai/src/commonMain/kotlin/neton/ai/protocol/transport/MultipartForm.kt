package neton.ai.protocol.transport

/**
 * multipart/form-data 的最小处理：只认 `name="…"` 的普通字段，够改写 `model` 一个字段就行。
 * 文件部分原样保留（可能几十 MB 的音频），所以全部按字节操作，不解码。
 */
@neton.ai.ExperimentalNetonAiApi
object MultipartForm {
    fun boundary(contentType: String?): String? {
        val ct = contentType ?: return null
        if (!ct.lowercase().startsWith("multipart/form-data")) return null
        val m = Regex("boundary=(?:\"([^\"]+)\"|([^;\\s]+))", RegexOption.IGNORE_CASE).find(ct) ?: return null
        return m.groupValues[1].ifEmpty { m.groupValues[2] }
    }

    /** 读一个文本字段的值；找不到返回 null。 */
    fun field(body: ByteArray, boundary: String, name: String): String? {
        val part = locate(body, boundary, name) ?: return null
        return body.copyOfRange(part.valueStart, part.valueEnd).decodeToString()
    }

    /** 把某个文本字段的值换掉；字段不存在则原样返回。 */
    fun replaceField(body: ByteArray, boundary: String, name: String, value: String): ByteArray {
        val part = locate(body, boundary, name) ?: return body
        val v = value.encodeToByteArray()
        val out = ByteArray(part.valueStart + v.size + (body.size - part.valueEnd))
        body.copyInto(out, 0, 0, part.valueStart)
        v.copyInto(out, part.valueStart)
        body.copyInto(out, part.valueStart + v.size, part.valueEnd, body.size)
        return out
    }

    private class Part(val valueStart: Int, val valueEnd: Int)

    private fun locate(body: ByteArray, boundary: String, name: String): Part? {
        val delim = "--$boundary".encodeToByteArray()
        val crlf2 = "\r\n\r\n".encodeToByteArray()
        var pos = indexOf(body, delim, 0)
        while (pos >= 0) {
            val headerStart = pos + delim.size
            if (headerStart + 2 <= body.size && body[headerStart] == '-'.code.toByte() && body[headerStart + 1] == '-'.code.toByte()) return null  // 结束边界
            val headerEnd = indexOf(body, crlf2, headerStart)
            if (headerEnd < 0) return null
            val headers = body.copyOfRange(headerStart, headerEnd).decodeToString()
            val valueStart = headerEnd + crlf2.size
            val next = indexOf(body, ("\r\n--$boundary").encodeToByteArray(), valueStart)
            if (next < 0) return null
            val isNamed = Regex("name=\"${Regex.escape(name)}\"", RegexOption.IGNORE_CASE).containsMatchIn(headers) && !headers.contains("filename=", ignoreCase = true)
            if (isNamed) return Part(valueStart, next)
            pos = next + 2
        }
        return null
    }

    fun indexOf(hay: ByteArray, needle: ByteArray, from: Int): Int {
        if (needle.isEmpty()) return from
        var i = maxOf(0, from)
        val last = hay.size - needle.size
        outer@ while (i <= last) {
            if (hay[i] == needle[0]) {
                for (j in 1 until needle.size) if (hay[i + j] != needle[j]) { i++; continue@outer }
                return i
            }
            i++
        }
        return -1
    }
}
