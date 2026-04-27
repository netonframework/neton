package neton.migrate.cli

sealed class Command {
    abstract val opts: CliOptions

    data class Status(override val opts: CliOptions) : Command()
    data class Up(override val opts: CliOptions) : Command()
    data class Verify(override val opts: CliOptions) : Command()
}

/**
 * CLI 选项 — 解析后传给 Command 执行。
 *
 * 配置来源优先级（决策 D1）：
 *   CLI flag > config/database.conf [default] 段 > 错误
 */
data class CliOptions(
    val driver: String? = null,           // sqlite / postgresql / mysql
    val uri: String? = null,
    val sqlDir: String? = null,           // 必填，指向 sql/{dialect}/ 这一层
    val configPath: String = "config",    // 默认 ./config
    val historyTable: String = "neton_schema_history"
)
