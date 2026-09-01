package neton.http.adapter

import neton.core.http.UploadFile
import neton.core.http.UploadFiles
import neton.core.http.Parameters

/**
 * 字节级 multipart/form-data 解析器（引擎无关）。
 *
 * 从原始请求体解析文件与文本字段，使所有 buffered 适配器（Hyper4k / Ktor）
 * 共享同一份 [UploadFiles] / [Parameters] 契约，不再依赖 Ktor 的流式 parseMultipart。
 * 语义与旧 Ktor 实现逐条对齐：name/filename 同时接受引号与 token 形态，
 * filename* 走 RFC 5987，无 filename 但带非 text/plain Content-Type 的 part 视为文件。
 */
internal object MultipartFormParser {

    private val BOUNDARY_REGEX = Regex("""boundary=("?)([^";\s]+)\1""")
    private val NAME_REGEX = Regex("""name=(?:"([^"]*)"|([^";\s]+))""")
    private val FILENAME_REGEX = Regex("""filename=(?:"([^"]*)"|([^";\s]+))""")
    private val FILENAME_STAR_REGEX = Regex("""filename\*=([^;\s]+)""")

    private val HEADER_END = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())

    fun parse(body: ByteArray, contentType: String): ParsedMultipart? {
        val boundary = BOUNDARY_REGEX.find(contentType)?.groupValues?.get(2) ?: return null
        val delimiter = "--$boundary".encodeToByteArray()

        val files = mutableListOf<UploadFile>()
        val formFields = linkedMapOf<String, MutableList<String>>()

        var cursor = indexOf(body, delimiter, 0)
        while (cursor >= 0) {
            var position = cursor + delimiter.size
            // 终止符 "--boundary--"
            if (position + 1 < body.size && body[position] == '-'.code.toByte() && body[position + 1] == '-'.code.toByte()) break
            // 分隔符后的 CRLF
            if (position + 1 < body.size && body[position] == '\r'.code.toByte() && body[position + 1] == '\n'.code.toByte()) {
                position += 2
            }
            val next = indexOf(body, delimiter, position)
            val partEnd = if (next < 0) body.size else next
            // part 体以 CRLF 结尾（位于下一个分隔符之前）
            val part = body.copyOfRange(position, partEnd)
            parsePart(part)?.let { (file, fieldName, text) ->
                if (file != null) {
                    files.add(file)
                } else if (fieldName.isNotEmpty()) {
                    formFields.getOrPut(fieldName) { mutableListOf() }.add(text.orEmpty())
                }
            }
            cursor = next
        }
        return ParsedMultipart(files, formFields)
    }

    private data class PartResult(val file: UploadFile?, val fieldName: String, val text: String?)

    private fun parsePart(part: ByteArray): PartResult? {
        val headerEnd = indexOf(part, HEADER_END, 0)
        if (headerEnd < 0) return null
        val headerText = part.copyOfRange(0, headerEnd).decodeToString()
        var body = part.copyOfRange(headerEnd + HEADER_END.size, part.size)
        // 去掉 part 体结尾的 CRLF
        if (body.size >= 2 && body[body.size - 2] == '\r'.code.toByte() && body[body.size - 1] == '\n'.code.toByte()) {
            body = body.copyOfRange(0, body.size - 2)
        }

        val disposition = headerText.lineSequence()
            .firstOrNull { it.startsWith("Content-Disposition:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()
            .orEmpty()
        val partContentType = headerText.lineSequence()
            .firstOrNull { it.startsWith("Content-Type:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()

        val name = NAME_REGEX.find(disposition)?.groupValues?.let { it[1].ifEmpty { it[2] } }.orEmpty()
        val filename = FILENAME_REGEX.find(disposition)?.groupValues?.let { it[1].ifEmpty { it[2] } }?.takeIf { it.isNotEmpty() }
            ?: FILENAME_STAR_REGEX.find(disposition)?.groupValues?.get(1)?.let { encoded ->
                // RFC 5987：charset''percent-encoded；旧 Ktor 实现漏了 percent 解码，
                // 非 ASCII 文件名会拿到 "%E5%9B%BE.jpg" 这种字面量，这里修正。
                BufferedHttpDispatcher.percentDecode(encoded.substringAfterLast("''").ifEmpty { encoded })
            }
            ?: if (partContentType != null && !partContentType.startsWith("text/plain")) "upload" else null

        return if (filename != null) {
            PartResult(
                file = BytesUploadFile(
                    fieldName = name,
                    filename = filename,
                    contentType = partContentType,
                    data = body,
                ),
                fieldName = name,
                text = null,
            )
        } else {
            PartResult(file = null, fieldName = name, text = body.decodeToString())
        }
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        var i = from
        while (i <= haystack.size - needle.size) {
            if (haystack[i] == needle[0]) {
                var matched = true
                for (j in 1 until needle.size) {
                    if (haystack[i + j] != needle[j]) {
                        matched = false
                        break
                    }
                }
                if (matched) return i
            }
            i++
        }
        return -1
    }
}

internal class ParsedMultipart(
    val files: List<UploadFile>,
    val formFields: Map<String, List<String>>,
) {
    fun asUploadFiles(): UploadFiles = UploadFiles(files)
    fun asParameters(): Parameters = MapParametersView(formFields)
}

private class BytesUploadFile(
    override val fieldName: String,
    override val filename: String,
    override val contentType: String?,
    private val data: ByteArray,
) : UploadFile {
    override val size: Long = data.size.toLong()
    override suspend fun bytes(): ByteArray = data
}

private class MapParametersView(
    private val data: Map<String, List<String>>,
) : Parameters {
    override fun get(name: String): String? = data[name]?.firstOrNull()
    override fun getAll(name: String): List<String> = data[name].orEmpty()
    override fun contains(name: String): Boolean = name in data
    override fun names(): Set<String> = data.keys
    override fun toMap(): Map<String, List<String>> = data
}
