package neton.database.migration

import neton.core.module.MigrationDialect
import neton.core.module.MigrationSource
import neton.database.api.DbContext

/**
 * Internalized migration engine(SPEC §0 / §五)。
 *
 * 边界:
 *   - 只吃 [DbContext](运行时 DB 访问门面) + [MigrationConfig] + 模块声明的 [MigrationSource] 列表
 *   - 不自己选 driver,不依赖 postgres / mysql / sqlite driver 包
 *   - 不做 println / log,所有输出走 [MigrationResult] 数据
 *
 * 调用方:
 *   - 正式入口: `application.kexe migrate` 子命令(DB-MIG-4 阶段接入)
 *   - 测试 / e2e: 直接构造 engine + DbContext(可用 SQLite memory)
 *
 * 单进程单 sqlx4k driver 约束(NETON-DB-VARIANT): engine 与 application serve
 * 模式共享同一个 DbContext / driver,自然不撞 rust_eh_personality 等链接器符号。
 */
class MigrationEngine(
    private val db: DbContext,
    private val config: MigrationConfig,
) {

    private val history = SchemaHistoryRepository(db, config)

    /**
     * 执行命令。Engine 是 pure 函数:
     *   - 不抛(scan 失败 / 连接失败由调用方提前确保;真出意外才抛)
     *   - 不写 stdout
     *   - 不调 exit
     */
    suspend fun run(command: MigrationCommand, sources: List<MigrationSource>): MigrationResult {
        val scan = scanAll(sources)
        if (scan is Either.Left) return scan.value
        return runWithScripts(command, scan.right.first, scan.right.second)
    }

    /**
     * 测试专用入口: 跳过文件扫描,直接给 engine 预先 scan 好的 [MigrationScript] 列表。
     * 生产代码走 [run],由 [scanAll] 调真实文件 IO。
     */
    internal suspend fun runWithScripts(
        command: MigrationCommand,
        scripts: List<MigrationScript>,
        warnings: List<String> = emptyList(),
    ): MigrationResult = when (command) {
        MigrationCommand.STATUS -> runStatus(scripts, warnings)
        MigrationCommand.UP -> runUp(scripts, warnings)
        MigrationCommand.VERIFY -> runVerify(scripts, warnings)
    }

    // ============================================================
    // STATUS
    // ============================================================

    private suspend fun runStatus(scripts: List<MigrationScript>, warnings: List<String>): MigrationResult {
        val historyExists = history.exists()
        if (!historyExists) {
            val views = scripts.map {
                MigrationResult.ScriptView(
                    moduleId = it.moduleId,
                    version = it.version,
                    description = it.description,
                    state = MigrationResult.ScriptState.PENDING,
                )
            }
            return MigrationResult.Status(
                historyTable = config.historyTable,
                historyExists = false,
                scripts = views,
                warnings = warnings,
            )
        }

        val executed = history.listAll()
        val executedKey = executed.associateBy { it.moduleId to it.version }
        val scriptKey = scripts.associateBy { it.moduleId to it.version }

        val views = mutableListOf<MigrationResult.ScriptView>()

        // 已执行(可能成功/失败/checksum 不一致/磁盘消失)
        for (e in executed) {
            val script = scriptKey[e.moduleId to e.version]
            val state = when {
                !e.success -> MigrationResult.ScriptState.FAILED
                script == null -> MigrationResult.ScriptState.MISSING_ON_DISK
                script.checksum != e.checksum -> MigrationResult.ScriptState.CHECKSUM_MISMATCH
                else -> MigrationResult.ScriptState.EXECUTED
            }
            views += MigrationResult.ScriptView(
                moduleId = e.moduleId,
                version = e.version,
                description = e.description.ifEmpty { script?.description ?: "" },
                state = state,
                diskChecksum = script?.checksum,
                historyChecksum = e.checksum,
                errorMessage = e.errorMessage,
            )
        }

        // 磁盘有但 history 没有 = pending
        for (s in scripts) {
            if ((s.moduleId to s.version) !in executedKey) {
                views += MigrationResult.ScriptView(
                    moduleId = s.moduleId,
                    version = s.version,
                    description = s.description,
                    state = MigrationResult.ScriptState.PENDING,
                )
            }
        }

        return MigrationResult.Status(
            historyTable = config.historyTable,
            historyExists = true,
            scripts = views,
            warnings = warnings,
        )
    }

    // ============================================================
    // VERIFY (只读)
    // ============================================================

    private suspend fun runVerify(scripts: List<MigrationScript>, warnings: List<String>): MigrationResult {
        if (!history.exists()) {
            return MigrationResult.Aborted(
                reason = "history table '${config.historyTable}' does not exist; nothing to verify",
                warnings = warnings,
            )
        }

        val executed = history.listAll().filter { it.success }
        val scriptKey = scripts.associateBy { it.moduleId to it.version }

        val mismatches = mutableListOf<MigrationResult.ScriptView>()
        val missing = mutableListOf<MigrationResult.ScriptView>()

        for (e in executed) {
            val script = scriptKey[e.moduleId to e.version]
            if (script == null) {
                missing += MigrationResult.ScriptView(
                    moduleId = e.moduleId,
                    version = e.version,
                    description = e.description,
                    state = MigrationResult.ScriptState.MISSING_ON_DISK,
                    historyChecksum = e.checksum,
                )
            } else if (script.checksum != e.checksum) {
                mismatches += MigrationResult.ScriptView(
                    moduleId = e.moduleId,
                    version = e.version,
                    description = e.description.ifEmpty { script.description },
                    state = MigrationResult.ScriptState.CHECKSUM_MISMATCH,
                    diskChecksum = script.checksum,
                    historyChecksum = e.checksum,
                )
            }
        }

        return MigrationResult.Verify(
            historyTable = config.historyTable,
            verifiedCount = executed.size,
            mismatches = mismatches,
            missing = missing,
            warnings = warnings,
        )
    }

    // ============================================================
    // UP
    // ============================================================

    private suspend fun runUp(scripts: List<MigrationScript>, warnings: List<String>): MigrationResult {
        history.ensureExists()

        val executed = history.listAll()
        val executedSuccessKey = executed.filter { it.success }.associateBy { it.moduleId to it.version }
        val executedFailed = executed.filter { !it.success }
        if (executedFailed.isNotEmpty()) {
            val items = executedFailed.joinToString(", ") { "[${it.moduleId}]V${it.version}" }
            return MigrationResult.Aborted(
                reason = "previously failed migrations require manual intervention: $items",
                warnings = warnings,
            )
        }

        // checksum 一致性预检 — 任何不一致立即中断,不进入 UP
        for (script in scripts) {
            val e = executedSuccessKey[script.moduleId to script.version] ?: continue
            if (script.checksum != e.checksum) {
                return MigrationResult.Aborted(
                    reason = "checksum mismatch on [${script.moduleId}]V${script.version}: " +
                        "disk=${script.checksum.take(8)} history=${e.checksum.take(8)}",
                    warnings = warnings,
                )
            }
        }

        val pending = scripts.filter { (it.moduleId to it.version) !in executedSuccessKey }
        if (pending.isEmpty()) {
            return MigrationResult.Up(
                historyTable = config.historyTable,
                applied = emptyList(),
                skipped = executedSuccessKey.size,
                warnings = warnings,
            )
        }

        val applied = mutableListOf<MigrationResult.AppliedScript>()

        for (script in pending) {
            val start = migrationCurrentTimeMillis()
            val statements = MigrationSqlSplitter.split(script.content)

            val outcome = runCatching {
                applyScript(statements)
            }
            val duration = migrationCurrentTimeMillis() - start

            if (outcome.isSuccess) {
                history.insert(
                    SchemaHistoryRepository.Row(
                        moduleId = script.moduleId,
                        version = script.version,
                        description = script.description,
                        checksum = script.checksum,
                        installedAt = start,
                        executionMs = duration,
                        success = true,
                        errorMessage = null,
                    )
                )
                applied += MigrationResult.AppliedScript(
                    moduleId = script.moduleId,
                    version = script.version,
                    description = script.description,
                    executionMs = duration,
                    success = true,
                )
            } else {
                val err = outcome.exceptionOrNull()?.message ?: "unknown error"
                runCatching {
                    history.insert(
                        SchemaHistoryRepository.Row(
                            moduleId = script.moduleId,
                            version = script.version,
                            description = script.description,
                            checksum = script.checksum,
                            installedAt = start,
                            executionMs = duration,
                            success = false,
                            errorMessage = err.take(2000),
                        )
                    )
                }
                val failed = MigrationResult.AppliedScript(
                    moduleId = script.moduleId,
                    version = script.version,
                    description = script.description,
                    executionMs = duration,
                    success = false,
                    errorMessage = err,
                )
                return MigrationResult.Up(
                    historyTable = config.historyTable,
                    applied = applied,
                    skipped = executedSuccessKey.size,
                    failedAt = failed,
                    warnings = warnings,
                )
            }
        }

        return MigrationResult.Up(
            historyTable = config.historyTable,
            applied = applied,
            skipped = executedSuccessKey.size,
            warnings = warnings,
        )
    }

    /**
     * 应用单个脚本。
     *
     * PG / SQLite: 包在事务里(BLOCKER-3 修复;sqlx4k Transactional API 用 pinned 连接,
     * 块内所有语句同一连接,块出错自动 rollback)。
     *
     * MySQL: 不开事务(DDL autocommit,即便开了也回滚不了),逐条 execute。任一失败抛,
     * 已执行的 DDL 部分无法回滚,依赖 caller 不混合 DDL+DML。
     */
    private suspend fun applyScript(statements: List<String>) {
        when (config.dialect) {
            MigrationDialect.POSTGRESQL, MigrationDialect.SQLITE -> {
                db.transaction {
                    for (stmt in statements) {
                        execute(stmt)
                    }
                }
            }
            MigrationDialect.MYSQL -> {
                for (stmt in statements) {
                    db.execute(stmt)
                }
            }
        }
    }

    // ============================================================
    // 内部: 扫描全部 sources, 过滤 dialect, 合并 warnings
    // ============================================================

    private fun scanAll(sources: List<MigrationSource>): Either<MigrationResult.Aborted, Pair<List<MigrationScript>, List<String>>> {
        val matched = sources.filter { it.dialect == config.dialect }
        val warnings = mutableListOf<String>()
        sources.filter { it.dialect != config.dialect }.forEach {
            warnings += "[${it.moduleId}] skipped source: dialect=${it.dialect.canonical} != engine=${config.dialect.canonical}"
        }

        val allScripts = mutableListOf<MigrationScript>()
        for (source in matched) {
            when (val r = MigrationScriptScanner.scan(source)) {
                is MigrationScriptScanner.ScanResult.Ok -> {
                    allScripts += r.scripts
                    warnings += r.warnings
                }
                is MigrationScriptScanner.ScanResult.DirNotFound ->
                    return Either.Left(MigrationResult.Aborted(
                        reason = "[${source.moduleId}] sql resource path not found: ${r.dir}",
                        warnings = warnings,
                    ))
                is MigrationScriptScanner.ScanResult.NotADirectory ->
                    return Either.Left(MigrationResult.Aborted(
                        reason = "[${source.moduleId}] sql resource path not a directory: ${r.dir}",
                        warnings = warnings,
                    ))
                is MigrationScriptScanner.ScanResult.DuplicateVersion ->
                    return Either.Left(MigrationResult.Aborted(
                        reason = "[${source.moduleId}] duplicate versions in ${source.resourcePath}: ${r.versions}",
                        warnings = warnings,
                    ))
            }
        }

        // 排序:
        //   - 同 module 内: 按 version 升序 (零填充避免 "10" < "2" 字典序坑) — frozen
        //   - 跨 module: 当前按 moduleId 字典序 (稳定 + 可预测)
        //
        // DB-MIG-3 接 ModuleInitializer.dependsOn 后, application 入口负责把 sources 按
        // 依赖拓扑序传进来. engine 在这里仍按 moduleId 字典 + version 排序作为 secondary
        // 稳定排序; primary 顺序由 List 顺序天然保持 (sortedWith 是稳定排序). 因此:
        //
        // **caller 约定 (DB-MIG-3 阶段强制)**: List<MigrationSource> 必须按模块依赖拓扑
        // 顺序传入 (depends-on 在前). engine 不验证拓扑, 也不重排.
        val maxVer = allScripts.maxOfOrNull { it.version.length } ?: 0
        val sorted = allScripts.sortedWith(
            compareBy({ it.moduleId }, { it.version.padStart(maxVer, '0') })
        )
        return Either.Right(sorted to warnings)
    }

    private sealed class Either<out L, out R> {
        data class Left<L>(val value: L) : Either<L, Nothing>()
        data class Right<R>(val value: R) : Either<Nothing, R>()

        val right: R get() = (this as Right<R>).value
    }
}
