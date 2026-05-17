package neton.migrate.command

import neton.migrate.cli.ExitCode
import neton.migrate.config.Driver
import neton.migrate.config.MigrateConfig
import neton.migrate.db.DbConnection
import neton.migrate.db.HistoryTable
import neton.migrate.io.currentTimeMillis
import neton.migrate.script.ScriptScanner
import neton.migrate.script.SqlSplitter

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
                    val statements = SqlSplitter.split(script.content)
                    when (db.driver) {
                        Driver.POSTGRESQL, Driver.SQLITE -> {
                            // 用 sqlx4k Transactional API (pinned 连接) - BLOCKER-3 fix.
                            // 原 db.execute("BEGIN") + 后续 db.execute(...) 因连接池
                            // 不 pin 导致 hang + autocommit drift; transaction { ... }
                            // 保证块内所有语句在同一连接, 块出错自动 rollback.
                            db.executeAllInTransaction(statements)
                        }
                        Driver.MYSQL -> {
                            // 不开事务 (DDL 在 MySQL 上自动 commit, 开了也没用);
                            // 逐条执行, 任一失败抛 -> 外层 runCatching 捕获 -> history
                            // 标 success=false. 注意 MySQL 上 DDL 部分已 commit 无法
                            // 回滚, 只能依赖 caller 不在同一 migration 里混 DDL+DML.
                            db.executeAllSequential(statements)
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

    // v1 fix (release proving BLOCKER-3 in privchat-application-module-game):
    //
    // 之前 v0.1 决策 D3 "整文件 execute" 在 sqlx4k PG driver 下静默吞 multi-statement
    // 脚本 (只执行第一条语句, 但返回 OK; 后续 ALTER / COMMENT 等被丢弃, 同时 history
    // 表也记录为成功) — 数据一致性灾难: caller 以为 schema 已演进但实际没有.
    //
    // 复现:
    //   V007__seat_status_remap_user_id_nullable.sql 内含
    //     ALTER TABLE game_table_seat ALTER COLUMN user_id DROP NOT NULL;
    //     COMMENT ON COLUMN game_table_seat.status IS '...';
    //     COMMENT ON COLUMN game_table_seat.user_id IS '...';
    //   `migrate up` 报 "OK", 但 \d game_table_seat 显示 user_id 仍 NOT NULL;
    //   neton_schema_history_* 也没写 V007 行 (sqlx4k 静默吞, runCatching success).
    //
    // v1 fix (两步):
    //   1. SqlSplitter 把脚本按 `;` 拆 (识别 string / 注释 / dollar-quoted block)
    //   2. PG/SQLite 走 db.executeAllInTransaction(...) (sqlx4k Transactional API,
    //      pinned 连接); MySQL 走 db.executeAllSequential(...) (DDL autocommit).
    //   3. 任一语句抛 → 外层 runCatching 捕获 → history.success=false + 退出码 2.
    //      PG/SQLite 整脚本 rollback; MySQL DDL 部分无法 rollback (driver 限制).
    //
    // 不变式 (BLOCKER-3 核心要求):
    //   - schema 实际未演进时, history 不会记 success=true.
    //   - 多语句 migration 完整执行, 不会只跑第一条.
}
