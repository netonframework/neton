package neton.cache

import neton.core.http.HandlerArgs

/**
 * v1 冻结：key 默认 hash(args) 的稳定、可重现实现。
 * 按参数声明顺序，包含 null；稳定编码；输出定长 hex（djb2 风格，KMP 无 MessageDigest 时可用）。
 */
object CacheKeyHash {
    private const val SEP = "|"
    private const val PREFIX_NULL = "n"
    private const val PREFIX_STR = "s:"
    private const val PREFIX_NUM = "v:"

    /**
     * 按 paramNames 顺序从 args 取值，稳定编码后做 hash，返回 hex 字符串（16 字符）。
     * 编码规则：null → "n"；String → "s:" + value；Number/Boolean → "v:" + value；其他 → "v:" + toString()。
     */
    fun stableHash(args: HandlerArgs, paramNames: List<String>): String {
        val parts = paramNames.map { name ->
            val v = args.first(name)
            when (v) {
                null -> PREFIX_NULL
                is String -> PREFIX_STR + v.replace("\\", "\\\\").replace(SEP, "\\|")
                is Number, is Boolean -> PREFIX_NUM + v.toString()
                else -> PREFIX_NUM + v.toString()
            }
        }
        val combined = parts.joinToString(SEP)
        return djb2Hex(combined)
    }

    /** djb2 风格 hash，输出 16 字符 hex（定长，可重现） */
    private fun djb2Hex(input: String): String {
        var h = 5381L
        for (c in input) {
            h = ((h shl 5) + h) + c.code
            h = h and 0x0FFFFFFFFFL
        }
        return h.toString(16).padStart(16, '0').takeLast(16)
    }
}
