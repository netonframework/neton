package neton.database.migration

import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import neton.core.module.MigrationDialect
import neton.core.module.MigrationScript
import neton.core.module.MigrationSource
import neton.database.api.DbContext

@OptIn(ExperimentalTime::class)
private fun currentTimeMillis(): Long = Clock.System.now().toEpochMilliseconds()

/**
 * Internalized migration engine (SPEC §0 / §五,2026-06-01 embed)。
 *
 * 边界:
 *   - 吃 [DbContext] + [MigrationConfig] + 模块声明的 [MigrationSource] 列表
 *   - 不读 filesystem — 所有 SQL 已 embed 到 binary 里(各模块 generate task 在编译期把
 *     `sql/<dialect>/V*.sql` 转成 Kotlin 字符串常量)
 *   - 不自己选 driver,不依赖 postgres / mysql / sqlite driver 包
 *   - 不做 println / log,所有输出走 [MigrationResult] 数据
 *
 * 调用方:
 *   - 正式入口: `application.kexe migrate` 子命令
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
     * 执行命令。Engine 是 pure 函数: 不抛、不写 stdout、不调 exit。
     */
    suspend fun run(command: MigrationCommand, sources: List<MigrationSource>): MigrationResult {
        val (scripts, warnings) = flatten(sources)
        return when (command) {
            MigrationCommand.STATUS -> runStatus(scripts, warnings)
            MigrationCommand.UP -> runUp(scripts, warnings)
            MigrationCommand.VERIFY -> runVerify(scripts, warnings)
        }
    }

    // ============================================================
    // STATUS
    // ============================================================

    private suspend fun runStatus(scripts: List<OwnedScript>, warnings: List<String>): MigrationResult {
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

        // embed 有但 history 没有 = pending
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

    private suspend fun runVerify(scripts: List<OwnedScript>, warnings: List<String>): MigrationResult {
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

    private suspend fun runUp(scripts: List<OwnedScript>, warnings: List<String>): MigrationResult {
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
                        "embed=${script.checksum.take(8)} history=${e.checksum.take(8)}",
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
            val start = currentTimeMillis()
            val statements = MigrationSqlSplitter.split(script.content)

            val outcome = runCatching {
                applyScript(statements)
            }
            val duration = currentTimeMillis() - start

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
    // 内部: flatten sources → OwnedScript 列表, 过滤 dialect, 排序
    // ============================================================

    /**
     * 把 [MigrationSource] 列表展平成 [OwnedScript] 列表(engine 内部工作类型,带 moduleId)。
     *
     * 排序:
     *   - 同 module 内: 按 version 升序(零填充避免 "10" < "2" 字典序坑) — frozen
     *   - 跨 module: caller 传入 List 顺序 = 模块拓扑序(application 负责),engine 用稳定
     *     sort 不重排
     *
     * Dialect 过滤: 与 [MigrationConfig.dialect] 不匹配的 source 被跳过,记入 warnings。
     */
    private fun flatten(sources: List<MigrationSource>): Pair<List<OwnedScript>, List<String>> {
        val warnings = mutableListOf<String>()
        val allScripts = mutableListOf<OwnedScript>()

        // 保留 caller 传入的模块顺序(拓扑序),flatMap 时保持稳定
        for ((moduleIdx, source) in sources.withIndex()) {
            if (source.dialect != config.dialect) {
                warnings += "[${source.moduleId}] skipped source: " +
                    "dialect=${source.dialect.canonical} != engine=${config.dialect.canonical}"
                continue
            }
            for (script in source.scripts) {
                allScripts += OwnedScript(
                    moduleOrder = moduleIdx,
                    moduleId = source.moduleId,
                    version = script.version,
                    description = script.description,
                    content = script.content,
                    checksum = script.checksum,
                )
            }
        }

        // 排序: 先按 caller 给的 module 顺序(moduleOrder), 同 module 内按 version 升序
        val maxVer = allScripts.maxOfOrNull { it.version.length } ?: 0
        val sorted = allScripts.sortedWith(
            compareBy({ it.moduleOrder }, { it.version.padStart(maxVer, '0') })
        )
        return sorted to warnings
    }

    /** Engine 内部工作类型: 带 moduleId 的 flattened script。 */
    private data class OwnedScript(
        val moduleOrder: Int,
        val moduleId: String,
        val version: String,
        val description: String,
        val content: String,
        val checksum: String,
    )
}

/**
 * 便利构造: 从公开的 [MigrationScript] (无 moduleId) 配合 moduleId 构造 [MigrationSource]。
 * 仅在测试 / 手写场景下用,生产代码模块走 Gradle 生成 sources。
 */
@Suppress("unused")
internal fun migrationSourceOf(
    moduleId: String,
    dialect: MigrationDialect,
    vararg scripts: MigrationScript,
): MigrationSource = MigrationSource(moduleId, dialect, scripts.toList())
