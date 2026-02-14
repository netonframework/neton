package neton.core.config

/**
 * 配置值来源（5.4 冻结）：用于类型错误等报错时的 source，避免 Env/ENV/env 分裂。
 */
enum class ConfigSource {
    FILE,
    ENV,
    CLI
}

/**
 * 配置解析错误（Neton-Core-Spec 5.4）：文件存在但 TOML 语法错误 → fail-fast。
 * 必须包含 file + line（+ 可选 col）便于排障。
 */
class ConfigParseException(
    val sourceName: String,
    val lineNumber: Int,
    val content: String,
    override val message: String,
    val column: Int? = null
) : Exception(buildString {
    append(sourceName)
    append(':')
    append(lineNumber)
    if (column != null) {
        append(':')
        append(column)
    }
    append(" ")
    append(message)
    append(": ")
    append(content)
})

/**
 * 配置类型错误（5.4）：typed 读取或 override 来源类型不匹配 → fail-fast。
 * 必须包含 path、expected、actual、source（FILE/ENV/CLI 枚举，避免字符串分裂）。
 */
class ConfigTypeException(
    val path: String,
    val expectedType: String,
    val actualValue: Any?,
    val source: ConfigSource,
    override val message: String = "config type error: path=$path expected=$expectedType actual=$actualValue source=$source"
) : Exception(message)
