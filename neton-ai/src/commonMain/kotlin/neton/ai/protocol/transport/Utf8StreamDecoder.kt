package neton.ai.protocol.transport

/**
 * 增量 UTF-8 解码：缓冲跨 chunk 被拆开的多字节序列尾部，避免逐 chunk `decodeToString()`
 * 在中文/emoji/JSON 字符跨网络 chunk 时产生替换字符（U+FFFD）破坏 JSON 与透传内容。
 *
 * 严格解码：完整前缀含非法 UTF-8、或 [flush] 时仍有不完整尾部（EOF 截断），都抛
 * [CharacterCodingException]，绝不吐替换字符 U+FFFD——调用方（producer）据此进入 PARTIAL。
 *
 * [decode] 返回到最近完整字符边界为止的已解码文本，未完成的尾部字节留待下一 chunk；
 * [flush] 在流结束时校验无残留（正常结束应为空；截断/非法则抛）。
 */
class Utf8StreamDecoder {
    private var pending = ByteArray(0)

    fun decode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val combined = if (pending.isEmpty()) bytes else pending + bytes
        val cut = completeLen(combined)
        pending = if (cut == combined.size) EMPTY else combined.copyOfRange(cut, combined.size)
        // throwOnInvalidSequence：完整前缀里的非法字节严格失败，不静默替换
        return if (cut == 0) "" else combined.decodeToString(0, cut, throwOnInvalidSequence = true)
    }

    fun flush(): String {
        if (pending.isEmpty()) return ""
        // EOF 仍有不完整/残缺字节 = 截断的 UTF-8：严格解码必然抛（不完整序列），绝不吐替换字符
        val p = pending
        pending = EMPTY
        return p.decodeToString(throwOnInvalidSequence = true)
    }

    /** 返回 [b] 中以完整 UTF-8 字符结尾的前缀长度；末尾若是不完整多字节序列则截在其 lead 字节前。 */
    private fun completeLen(b: ByteArray): Int {
        var i = b.size - 1
        var cont = 0
        while (i >= 0 && (b[i].toInt() and 0xC0) == 0x80 && cont < 3) { cont++; i-- }
        if (i < 0) return 0  // 全是续接字节（异常）：整体缓冲等待更多
        val lead = b[i].toInt() and 0xFF
        val need = when {
            lead < 0x80 -> 1
            lead and 0xE0 == 0xC0 -> 2
            lead and 0xF0 == 0xE0 -> 3
            lead and 0xF8 == 0xF0 -> 4
            else -> 1  // 非法 lead：按 1 字节放行（交由 decodeToString 出替换字符）
        }
        return if (cont + 1 >= need) b.size else i
    }

    private companion object {
        val EMPTY = ByteArray(0)
    }
}
