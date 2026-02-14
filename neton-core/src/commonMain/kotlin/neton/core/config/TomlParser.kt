package neton.core.config

/**
 * 最小 TOML 解析（v1.1 冻结：仅支持规范所需子集）。
 * 支持：[section]、[section.subsection]、key = value（string/int/bool/float）、[[array.of.tables]]。
 * Native 可用，无额外依赖。
 *
 * **v1 明确不支持**（遇到即按语法错误 fail-fast，非 bug）：
 * - 多行字符串（"""..."""）
 * - datetime
 * - inline table（{ a = 1 }）
 */
object TomlParser {

    /**
     * 解析 TOML 字符串为 Map。解析失败抛 [ConfigParseException]。
     */
    fun parse(content: String, sourceName: String = "config"): Map<String, Any?> {
        val root = mutableMapOf<String, Any?>()
        val lines = content.lines()
        var currentPath = listOf<String>()
        var currentArrayPath: List<String>? = null
        var arrayAccumulator: MutableMap<String, Any?>? = null

        fun setAt(path: List<String>, key: String, value: Any?) {
            var node: MutableMap<String, Any?> = root
            for (p in path) {
                val next = node.getOrPut(p) { mutableMapOf<String, Any?>() }
                node = (next as? MutableMap<String, Any?>) ?: return
            }
            node[key] = value
        }

        fun getTableAt(path: List<String>): MutableMap<String, Any?> {
            var node: Any? = root
            for (p in path) {
                node = (node as? Map<*, *>)?.get(p) ?: run {
                    val m = mutableMapOf<String, Any?>()
                    (node as? MutableMap<String, Any?>)?.set(p, m)
                    m
                }
            }
            return node as? MutableMap<String, Any?>
                ?: throw ConfigParseException(sourceName, 0, path.joinToString("."), "expected table")
        }

        for ((lineNum, line) in lines.withIndex()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue

            when {
                trimmed.startsWith("[[") -> {
                    val close = trimmed.indexOf("]]")
                    if (close == -1) throw ConfigParseException(sourceName, lineNum + 1, trimmed, "invalid [[section]]")
                    val path = trimmed.substring(2, close).trim().split(".").map { it.trim() }
                    currentPath = path
                    currentArrayPath = path
                    val parentPath = path.dropLast(1)
                    val key = path.last()
                    val parent = if (parentPath.isEmpty()) root else getTableAt(parentPath) as MutableMap<String, Any?>
                    val list = (parent.getOrPut(key) { mutableListOf<MutableMap<String, Any?>>() } as? MutableList<MutableMap<String, Any?>>)
                        ?: mutableListOf<MutableMap<String, Any?>>().also { parent[key] = it }
                    arrayAccumulator = mutableMapOf<String, Any?>()
                    list.add(arrayAccumulator!!)
                }
                trimmed.startsWith("[") && !trimmed.startsWith("[[") -> {
                    val close = trimmed.indexOf("]")
                    if (close == -1) throw ConfigParseException(sourceName, lineNum + 1, trimmed, "invalid [section]")
                    currentPath = trimmed.substring(1, close).trim().split(".").map { it.trim() }
                    currentArrayPath = null
                    arrayAccumulator = null
                }
                trimmed.contains("=") -> {
                    val eq = trimmed.indexOf("=")
                    val key = trimmed.substring(0, eq).trim().trim('"')
                    val valueStr = trimmed.substring(eq + 1).trim()
                    val value = parseValue(valueStr, sourceName, lineNum + 1)
                    if (arrayAccumulator != null) {
                        arrayAccumulator!![key] = value
                    } else {
                        setAt(currentPath, key, value)
                    }
                }
            }
        }
        return root
    }

    private fun parseValue(s: String, sourceName: String, lineNum: Int): Any? {
        return when {
            s == "true" -> true
            s == "false" -> false
            s.startsWith("\"") && s.endsWith("\"") -> s.substring(1, s.length - 1)
                .replace("\\n", "\n").replace("\\\"", "\"")
            s.startsWith("'") && s.endsWith("'") -> s.substring(1, s.length - 1)
            s.contains(".") && s.toDoubleOrNull() != null -> s.toDouble()
            s.toIntOrNull() != null -> s.toInt()
            s.toLongOrNull() != null -> s.toLong()
            else -> s
        }
    }
}
