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
 * neton-migrate v0.1
 *
 * 边界（与 spec 一致）：
 *   - 独立 binary，不接入 app startup
 *   - 只做 status / up / verify
 *   - 不做 down / dry-run / baseline / 多节点锁 / 远程 SQL
 *   - 不依赖 neton-database
 */
fun main(args: Array<String>) {
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
