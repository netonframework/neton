package neton.migrate

import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking
import neton.migrate.cli.ArgParser
import neton.migrate.cli.Command
import neton.migrate.cli.ExitCode
import neton.migrate.command.StatusCommand
import neton.migrate.command.UpCommand
import neton.migrate.command.VerifyCommand
import neton.migrate.config.MigrateConfigResolver

/**
 * neton-migrate v0.1 — **DEPRECATED as the primary Neton application migration entry**
 *
 * 状态(2026-06-01,DB-MIG-6):
 *   - 不再作为 Neton application 的正式部署入口
 *   - Canonical entry 已切到 `./application.kexe migrate up | status | verify`
 *     (SPEC §0 / §五;engine 在 neton-database/.../migration/)
 *   - 本 binary 仅保留给 low-level / offline / debug 场景:
 *       * 无 application 上下文调试任意 `--driver` / `--uri` / `--dir`
 *       * 手动修复历史 schema
 *       * Neton 框架自身 e2e 测试夹具
 *   - 不要在新部署 runbook / CI / 教程里写本 binary
 *
 * 历史边界(与 spec 一致):
 *   - 独立 binary,不接入 app startup
 *   - 只做 status / up / verify
 *   - 不做 down / dry-run / baseline / 多节点锁 / 远程 SQL
 *   - 不依赖 neton-database
 */
fun main(args: Array<String>) {
    println(
        "WARNING: neton-migrate is deprecated as the primary Neton application migration entry.\n" +
            "         Canonical entry: ./application.kexe migrate <status|up|verify>\n" +
            "         See: neton-docs/docs/spec/migration.md §五 / 附录 C"
    )
    val exitCode = runBlocking { run(args) }
    exitProcess(exitCode)
}

private suspend fun run(args: Array<String>): Int {
    val parsed = ArgParser.parse(args)
    when (parsed) {
        is ArgParser.ParseResult.Help -> {
            parsed.errorMessage?.let {
                println("ERROR: $it")
                println()
            }
            println(ArgParser.USAGE)
            return if (parsed.errorMessage != null) ExitCode.USAGE_ERROR else ExitCode.OK
        }
        is ArgParser.ParseResult.Ok -> {
            val command = parsed.command
            val resolveResult = MigrateConfigResolver.resolve(command.opts)
            val config = when (resolveResult) {
                is MigrateConfigResolver.Result.Ok -> resolveResult.config
                is MigrateConfigResolver.Result.MissingArg -> {
                    println("ERROR: ${resolveResult.message}")
                    println()
                    println(ArgParser.USAGE)
                    return ExitCode.USAGE_ERROR
                }
            }

            return when (command) {
                is Command.Status -> StatusCommand.run(config)
                is Command.Up -> UpCommand.run(config)
                is Command.Verify -> VerifyCommand.run(config)
            }
        }
    }
}
