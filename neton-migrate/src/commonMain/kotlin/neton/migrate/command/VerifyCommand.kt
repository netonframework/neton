package neton.migrate.command

import neton.migrate.cli.ExitCode
import neton.migrate.config.MigrateConfig
import neton.migrate.db.DbConnection
import neton.migrate.db.HistoryTable
import neton.migrate.script.ScriptScanner

/**
 * verify — 只读校验已执行脚本 checksum。
 *
 * 行为：
 *   - 扫描脚本
 *   - 连库；连不上 → 退出 4
 *   - history 表不存在 → 退出 3（决策额外冻结 #3）
 *   - 比对每条已执行 history 的 checksum 与磁盘上脚本的 checksum
 *
 * 退出码：
 *   0 = 全一致
 *   3 = 有不一致 / history 表不存在
 *   4 = 连不上
 */
object VerifyCommand {

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

        val db = DbConnection.connect(config).getOrElse {
            println("ERROR: cannot connect to database: ${it.message}")
            return ExitCode.DB_CONNECT_FAILED
        }

        try {
            val historyExists = HistoryTable.exists(db, config.historyTable)
            if (!historyExists) {
                println("ERROR: schema history table '${config.historyTable}' does not exist — nothing to verify")
                return ExitCode.CHECKSUM_MISMATCH
            }

            val executed = HistoryTable.listAll(db, config.historyTable).filter { it.success }
            val scriptByVersion = scripts.associateBy { it.version }

            var mismatchCount = 0
            var missingCount = 0

            for (e in executed) {
                val s = scriptByVersion[e.version]
                when {
                    s == null -> {
                        missingCount++
                        println("MISSING: V${e.version} (${e.description}) — recorded in history but no file on disk")
                    }
                    s.checksum != e.checksum -> {
                        mismatchCount++
                        println("MISMATCH: V${e.version} (${e.description})")
                        println("  disk:    ${s.checksum}")
                        println("  history: ${e.checksum}")
                    }
                }
            }

            println()
            println("Verified ${executed.size} executed script(s): $mismatchCount mismatched, $missingCount missing")

            return if (mismatchCount > 0 || missingCount > 0) {
                ExitCode.CHECKSUM_MISMATCH
            } else {
                println("All checksums match.")
                ExitCode.OK
            }
        } finally {
            db.close()
        }
    }
}
