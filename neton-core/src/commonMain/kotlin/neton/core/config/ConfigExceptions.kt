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

/**
 * 一条没有被任何配置消费的覆盖项（Neton-Core-Spec 5.4.2）。
 *
 * @property source CLI 或 ENV
 * @property arg 原始写法：`--default.uri=...` 的 `--default.uri`，或 `NETON_DEFAULT__URI`
 * @property path 点分路径（`default.uri`）
 * @property root 第一段（`default`）——它既不是 application.conf 的根段，也不是任何模块命名空间
 * @property hint 能推断出本意时给出正确写法（`--database.default.uri`），否则 null
 */
data class UnknownConfigOverride(
    val source: ConfigSource,
    val arg: String,
    val path: String,
    val root: String,
    val hint: String?,
) {
    /** 机器可解析的一行：`source=CLI arg=--default.uri root=default hint=--database.default.uri`。 */
    fun describe(): String = buildString {
        append("source=").append(source)
        append(" arg=").append(arg)
        append(" root=").append(root)
        if (hint != null) append(" hint=").append(hint)
    }
}

/**
 * CLI 覆盖没有落到任何配置上（5.4.2 fail-fast）。
 *
 * 只有 CLI 会抛：命令行是显式意图，静默忽略等于让进程连到错误的库/端口。
 * 进程环境里的 `NETON_*` 可能来自共享 shell、目标是本宿主没装配的模块，只告警。
 */
class ConfigOverrideException(
    val overrides: List<UnknownConfigOverride>,
) : Exception(
    "$CODE " + overrides.joinToString("; ") { it.describe() } +
        " (module overrides must be namespaced: --<namespace>.<key>=..., e.g. --database.default.uri=...)"
) {
    companion object {
        /** 错误码：日志/脚本按它判别，不依赖文案。 */
        const val CODE: String = "CONFIG_OVERRIDE_UNKNOWN_ROOT"
    }
}
