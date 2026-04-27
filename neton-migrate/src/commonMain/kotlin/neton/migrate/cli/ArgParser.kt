package neton.migrate.cli

object ArgParser {

    /**
     * 解析命令行参数。
     *
     * 用法：
     *   neton-migrate <command> [--driver D] [--uri U] [--dir SQLDIR] [--config DIR] [--history-table T]
     *
     * 支持的命令：status / up / verify
     */
    fun parse(args: Array<String>): ParseResult {
        if (args.isEmpty()) return ParseResult.Help("missing command")

        val command = args[0]
        if (command in listOf("-h", "--help", "help")) return ParseResult.Help(null)

        val flags = parseFlags(args.drop(1))
        val opts = CliOptions(
            driver = flags["driver"],
            uri = flags["uri"],
            sqlDir = flags["dir"],
            configPath = flags["config"] ?: "config",
            historyTable = flags["history-table"] ?: "neton_schema_history"
        )

        return when (command) {
            "status" -> ParseResult.Ok(Command.Status(opts))
            "up" -> ParseResult.Ok(Command.Up(opts))
            "verify" -> ParseResult.Ok(Command.Verify(opts))
            else -> ParseResult.Help("unknown command: $command")
        }
    }

    private fun parseFlags(tokens: List<String>): Map<String, String> {
        val out = mutableMapOf<String, String>()
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            if (!t.startsWith("--")) {
                i++
                continue
            }
            val key = t.removePrefix("--")
            val eqIdx = key.indexOf('=')
            if (eqIdx > 0) {
                out[key.substring(0, eqIdx)] = key.substring(eqIdx + 1)
                i++
            } else if (i + 1 < tokens.size && !tokens[i + 1].startsWith("--")) {
                out[key] = tokens[i + 1]
                i += 2
            } else {
                out[key] = "true"
                i++
            }
        }
        return out
    }

    sealed class ParseResult {
        data class Ok(val command: Command) : ParseResult()
        data class Help(val errorMessage: String?) : ParseResult()
    }

    val USAGE = """
        |neton-migrate v0.1 — Database schema migration CLI
        |
        |Usage:
        |  neton-migrate <command> [options]
        |
        |Commands:
        |  status            List executed and pending scripts (read-only)
        |  up                Execute pending scripts in version order
        |  verify            Verify checksums of executed scripts (read-only)
        |
        |Options:
        |  --driver D            Database driver: sqlite | postgresql | mysql
        |  --uri U               Connection URI (e.g. mysql://user:pass@host:3306/db)
        |  --dir DIR             Directory containing V*.sql scripts (e.g. sql/mysql)
        |  --config DIR          Config directory to read database.conf from (default: config)
        |  --history-table T     History table name (default: neton_schema_history)
        |
        |Configuration sources (in order of precedence):
        |  1. CLI flags (--driver / --uri)
        |  2. config/database.conf [default] section
        |
        |Exit codes:
        |  0  OK / nothing to do
        |  1  status: pending scripts found
        |  2  up: execution failed
        |  3  checksum mismatch / history table missing (verify)
        |  4  database connection failed
        |  64 usage error
        |
        |Spec: https://netonframework.github.io/spec/migration
    """.trimMargin()
}
