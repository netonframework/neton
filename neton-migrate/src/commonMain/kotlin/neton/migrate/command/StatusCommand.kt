package neton.migrate.command

import neton.migrate.cli.ExitCode
import neton.migrate.config.MigrateConfig
import neton.migrate.db.DbConnection
import neton.migrate.db.HistoryTable
import neton.migrate.script.ScriptFile
import neton.migrate.script.ScriptScanner

/**
 * status — 只读。
 *
 * 行为：
 *   - 扫描 sql 目录得到 scripts
 *   - 连库；连不上 → 退出 4
 *   - 检查 history 表是否存在
 *     · 不存在 → 显示"未初始化，全部 N 个脚本待执行"，退出 1（如果有脚本），退出 0（如果 0 个）
 *     · 存在 → 对比已执行 / 未执行 / checksum 不一致
 *
 * 退出码：
 *   0 = 全部已执行 + checksum 一致
 *   1 = 有未执行
 *   3 = 有 checksum 不一致
 *   4 = 连不上
 */
object StatusCommand {

    suspend fun run(config: MigrateConfig): Int {
        // 扫描脚本
        val scanResult = ScriptScanner.scan(config.sqlDir)
        val scripts: List<ScriptFile> = when (scanResult) {
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

        // 连库
        val db = DbConnection.connect(config).getOrElse {
            println("ERROR: cannot connect to database: ${it.message}")
            return ExitCode.DB_CONNECT_FAILED
        }

        try {
            val historyExists = HistoryTable.exists(db, config.historyTable)
            if (!historyExists) {
                println("Schema history not initialized (table '${config.historyTable}' does not exist).")
                println()
                println("Pending scripts: ${scripts.size}")
                scripts.forEach { println("  [pending] V${it.version}  ${it.description}  (${it.fileName})") }
                return if (scripts.isEmpty()) ExitCode.OK else ExitCode.PENDING
            }

            val executed = HistoryTable.listAll(db, config.historyTable)
            val executedByVersion = executed.associateBy { it.version }

            var pending = 0
            var mismatch = 0
            var failed = 0

            println("Schema history table: ${config.historyTable}")
            println()

            // 已执行的脚本
            for (e in executed) {
                val script = scripts.find { it.version == e.version }
                when {
                    !e.success -> {
                        failed++
                        println("  [FAILED]   V${e.version}  ${e.description}  (${e.errorMessage ?: "no error message"})")
                    }
                    script == null -> {
                        println("  [executed] V${e.version}  ${e.description}  (script file missing on disk)")
                    }
                    script.checksum != e.checksum -> {
                        mismatch++
                        println("  [CHANGED]  V${e.version}  ${e.description}  (checksum mismatch: disk=${script.checksum.take(8)} db=${e.checksum.take(8)})")
                    }
                    else -> {
                        println("  [executed] V${e.version}  ${e.description}")
                    }
                }
            }

            // 未执行的脚本
            for (s in scripts) {
                if (s.version !in executedByVersion) {
                    pending++
                    println("  [pending]  V${s.version}  ${s.description}  (${s.fileName})")
                }
            }

            println()
            println("Summary: ${executed.size - failed} executed, $pending pending, $mismatch changed, $failed failed")

            return when {
                mismatch > 0 -> ExitCode.CHECKSUM_MISMATCH
                pending > 0 -> ExitCode.PENDING
                else -> ExitCode.OK
            }
        } finally {
            db.close()
        }
    }
}
