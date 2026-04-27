package neton.migrate.command

import neton.migrate.cli.ExitCode
import neton.migrate.config.Driver
import neton.migrate.config.MigrateConfig
import neton.migrate.db.DbConnection
import neton.migrate.db.HistoryTable
import neton.migrate.io.currentTimeMillis
import neton.migrate.script.ScriptScanner

/**
 * up — 顺序执行未执行脚本。
 *
 * 行为：
 *   - 扫描脚本
 *   - 连库；连不上 → 退出 4
 *   - 确保 history 表存在（这是 up 的职责，不是 status 的）
 *   - 比对 history，找出未执行的
 *   - 校验已执行脚本 checksum；任何不一致 → 退出 3（停止，不继续执行）
 *   - 顺序执行未执行脚本：
 *     · PG/SQLite: 包在事务内（决策 D4）
 *     · MySQL: 不开事务（DDL 自动 commit）
 *     · 失败 → 写 history(success=false) → 退出 2
 *   - 全部成功 → 退出 0
 */
object UpCommand {

    suspend fun run(config: MigrateConfig): Int {
        val scanResult = ScriptScanner.scan(config.sqlDir)
        val scripts = when (scanResult) {
            is ScriptScanner.ScanResult.Ok -> scanResult.scripts
            is ScriptScanner.ScanResult.DirNotFound -> {
                println("ERROR: directory not found: ${scanResult.dir}")
                return ExitCode.USAGE_ERROR
            }
            is ScriptScanner.ScanResult.NotADirectory -> {
                println("ERROR: not a directory: ${scanResult.dir}")
                return ExitCode.USAGE_ERROR
            }
            is ScriptScanner.ScanResult.DuplicateVersion -> {
                println("ERROR: duplicate versions in ${config.sqlDir}: ${scanResult.versions}")
                return ExitCode.USAGE_ERROR
            }
        }
        if (scanResult is ScriptScanner.ScanResult.Ok && scanResult.warnings.isNotEmpty()) {
            scanResult.warnings.forEach { println("WARN: $it") }
        }

        val db = DbConnection.connect(config).getOrElse {
            println("ERROR: cannot connect to database: ${it.message}")
            return ExitCode.DB_CONNECT_FAILED
        }

        try {
            HistoryTable.ensureExists(db, config.historyTable)

            val executed = HistoryTable.listAll(db, config.historyTable)
            val executedByVersion = executed.filter { it.success }.associateBy { it.version }

            // checksum 一致性检查 — 任何不一致即停
            for (e in executed.filter { it.success }) {
                val s = scripts.find { it.version == e.version } ?: continue
                if (s.checksum != e.checksum) {
                    println("ERROR: checksum mismatch on V${e.version} (${e.description})")
                    println("  disk:    ${s.checksum}")
                    println("  history: ${e.checksum}")
                    println("Fix: revert the script to its original content, or create a new V*.sql to evolve.")
                    return ExitCode.CHECKSUM_MISMATCH
                }
            }

            // 检查有无失败留痕（要求人工介入）
            val failed = executed.filter { !it.success }
            if (failed.isNotEmpty()) {
                println("ERROR: there are previously failed migrations requiring manual intervention:")
                failed.forEach { println("  V${it.version}  ${it.description}  (${it.errorMessage ?: "no error message"})") }
                return ExitCode.EXECUTION_FAILED
            }

            val pending = scripts.filter { it.version !in executedByVersion }
            if (pending.isEmpty()) {
                println("Nothing to migrate. ${executed.size} script(s) already executed.")
                return ExitCode.OK
            }

            println("Migrating ${pending.size} pending script(s)...")
            println()

            for (script in pending) {
                println(">> V${script.version}  ${script.description}  (${script.fileName})")
                val start = currentTimeMillis()

                val result = runCatching {
                    when (db.driver) {
                        Driver.POSTGRESQL, Driver.SQLITE -> {
                            // 包事务
                            db.execute("BEGIN")
                            try {
                                executeScript(db, script.content)
                                db.execute("COMMIT")
                            } catch (e: Throwable) {
                                runCatching { db.execute("ROLLBACK") }
                                throw e
                            }
                        }
                        Driver.MYSQL -> {
                            // 不开事务（DDL 在 MySQL 上自动 commit，开了也没用）
                            executeScript(db, script.content)
                        }
                    }
                }

                val duration = currentTimeMillis() - start
                if (result.isSuccess) {
                    HistoryTable.insert(db, config.historyTable, HistoryTable.Row(
                        version = script.version,
                        description = script.description,
                        script = script.fileName,
                        checksum = script.checksum,
                        executedAt = start,
                        durationMs = duration,
                        success = true,
                        errorMessage = null
                    ))
                    println("   OK  (${duration}ms)")
                } else {
                    val err = result.exceptionOrNull()?.message ?: "unknown error"
                    runCatching {
                        HistoryTable.insert(db, config.historyTable, HistoryTable.Row(
                            version = script.version,
                            description = script.description,
                            script = script.fileName,
                            checksum = script.checksum,
                            executedAt = start,
                            durationMs = duration,
                            success = false,
                            errorMessage = err.take(2000)
                        ))
                    }
                    println("   FAILED  (${duration}ms): $err")
                    println()
                    println("Aborted. ${pending.indexOf(script)} of ${pending.size} migrations applied successfully.")
                    return ExitCode.EXECUTION_FAILED
                }
            }

            println()
            println("Done. ${pending.size} migration(s) applied.")
            return ExitCode.OK
        } finally {
            db.close()
        }
    }

    /**
     * 决策 D3：先尝试整文件 execute；如果 driver 不接受多语句脚本，
     * v0.2 再加 splitter。v0.1 简单直接。
     */
    private suspend fun executeScript(db: DbConnection, content: String) {
        db.execute(content)
    }
}
