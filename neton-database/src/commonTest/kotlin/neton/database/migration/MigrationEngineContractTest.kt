package neton.database.migration

import neton.core.module.MigrationDialect
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DB-MIG-2 — Engine state-matrix contract frozen.
 *
 * 覆盖:
 *   - STATUS 5 个状态 (PENDING / EXECUTED / CHECKSUM_MISMATCH / FAILED / MISSING_ON_DISK)
 *   - FAILED 优先级吃掉同行的 CHECKSUM_MISMATCH
 *   - UP 三道防御 (failed-row / checksum-mismatch / pending-empty)
 *   - VERIFY 三种结果 (无表 / 全 ok / mismatch+missing)
 *   - 所有命令路径都按 (module_id, version) 复合键 lookup
 *
 * 用 [FakeMigrationDb] 注入 history 状态;脚本走 [MigrationEngine.runWithScripts]
 * 跳过文件 IO。
 */
class MigrationEngineContractTest {

    private val config = MigrationConfig(
        dialect = MigrationDialect.POSTGRESQL,
        historyTable = "test_migrations",
    )

    private fun script(module: String, version: String, checksum: String): MigrationScript =
        MigrationScript(
            moduleId = module,
            version = version,
            description = "test_$version",
            fileName = "V$version" + "__test_$version.sql",
            absolutePath = "/fake/V$version" + "__test_$version.sql",
            content = "-- fake content for $module V$version $checksum",
            checksum = checksum,
        )

    private fun history(
        module: String,
        version: String,
        checksum: String,
        success: Boolean = true,
        error: String? = null,
    ): SchemaHistoryRepository.Row = SchemaHistoryRepository.Row(
        moduleId = module,
        version = version,
        description = "test_$version",
        checksum = checksum,
        installedAt = 1700000000_000L,
        executionMs = 5L,
        success = success,
        errorMessage = error,
    )

    // ============================================================
    // STATUS state matrix (audit 点 #4 + #3)
    // ============================================================

    @Test
    fun status_noHistoryTable_allScriptsArePending() = runTest {
        val db = FakeMigrationDb(initialRows = null)
        val engine = MigrationEngine(db, config)
        val scripts = listOf(script("game", "001", "ck1"), script("game", "002", "ck2"))

        val r = engine.runWithScripts(MigrationCommand.STATUS, scripts) as MigrationResult.Status
        assertEquals(false, r.historyExists)
        assertEquals(2, r.pendingCount)
        assertEquals(0, r.executedCount)
        assertEquals(0, r.mismatchCount)
        assertEquals(0, r.failedCount)
        assertTrue(r.scripts.all { it.state == MigrationResult.ScriptState.PENDING })
    }

    @Test
    fun status_executed_matchingChecksum() = runTest {
        val db = FakeMigrationDb(initialRows = listOf(history("game", "001", "ck1")))
        val engine = MigrationEngine(db, config)
        val r = engine.runWithScripts(
            MigrationCommand.STATUS,
            listOf(script("game", "001", "ck1")),
        ) as MigrationResult.Status
        assertEquals(1, r.executedCount)
        assertEquals(0, r.pendingCount)
    }

    @Test
    fun status_checksumMismatch_detected() = runTest {
        val db = FakeMigrationDb(initialRows = listOf(history("game", "001", "history_ck")))
        val engine = MigrationEngine(db, config)
        val r = engine.runWithScripts(
            MigrationCommand.STATUS,
            listOf(script("game", "001", "disk_ck")),
        ) as MigrationResult.Status
        assertEquals(1, r.mismatchCount)
        val view = r.scripts.first()
        assertEquals(MigrationResult.ScriptState.CHECKSUM_MISMATCH, view.state)
        assertEquals("disk_ck", view.diskChecksum)
        assertEquals("history_ck", view.historyChecksum)
    }

    @Test
    fun status_missingOnDisk_detected() = runTest {
        val db = FakeMigrationDb(initialRows = listOf(history("game", "001", "ck1")))
        val engine = MigrationEngine(db, config)
        val r = engine.runWithScripts(
            MigrationCommand.STATUS,
            scripts = emptyList(), // 磁盘空
        ) as MigrationResult.Status
        assertEquals(0, r.pendingCount)
        assertEquals(0, r.executedCount)
        val view = r.scripts.first()
        assertEquals(MigrationResult.ScriptState.MISSING_ON_DISK, view.state)
        assertNull(view.diskChecksum)
    }

    @Test
    fun status_failed_isReported() = runTest {
        val db = FakeMigrationDb(
            initialRows = listOf(history("game", "001", "ck1", success = false, error = "boom"))
        )
        val engine = MigrationEngine(db, config)
        val r = engine.runWithScripts(
            MigrationCommand.STATUS,
            listOf(script("game", "001", "ck1")),
        ) as MigrationResult.Status
        assertEquals(1, r.failedCount)
        val view = r.scripts.first()
        assertEquals(MigrationResult.ScriptState.FAILED, view.state)
        assertEquals("boom", view.errorMessage)
    }

    /**
     * Audit 点 #4 (优先级 contract): FAILED 优先于 CHECKSUM_MISMATCH。
     * 即使 history.success=false **且** 磁盘 checksum 也漂移,status 报 FAILED。
     */
    @Test
    fun status_failedPriority_overridesChecksumMismatch() = runTest {
        val db = FakeMigrationDb(
            initialRows = listOf(history("game", "001", "old_ck", success = false, error = "boom"))
        )
        val engine = MigrationEngine(db, config)
        val r = engine.runWithScripts(
            MigrationCommand.STATUS,
            listOf(script("game", "001", "new_ck")),
        ) as MigrationResult.Status
        assertEquals(1, r.failedCount)
        assertEquals(0, r.mismatchCount, "FAILED must take priority over CHECKSUM_MISMATCH")
        assertEquals(MigrationResult.ScriptState.FAILED, r.scripts.first().state)
    }

    /**
     * Audit 点 #5: (module_id, version) 复合键。
     * 不同 module 的同 version 不冲突。
     */
    @Test
    fun status_crossModule_sameVersion_doesNotCollide() = runTest {
        val db = FakeMigrationDb(
            initialRows = listOf(
                history("payment", "001", "pay_ck"),
                history("member", "001", "mem_ck"),
            )
        )
        val engine = MigrationEngine(db, config)
        val r = engine.runWithScripts(
            MigrationCommand.STATUS,
            listOf(
                script("payment", "001", "pay_ck"),
                script("member", "001", "mem_ck"),
                script("game", "001", "game_ck"), // new module → pending
            ),
        ) as MigrationResult.Status
        assertEquals(2, r.executedCount)
        assertEquals(1, r.pendingCount)
        assertEquals(0, r.mismatchCount)
    }

    // ============================================================
    // UP 防御 (audit 点 #2)
    // ============================================================

    @Test
    fun up_failedRow_aborts_noManualRetry() = runTest {
        val db = FakeMigrationDb(
            initialRows = listOf(history("game", "001", "ck", success = false, error = "boom"))
        )
        val engine = MigrationEngine(db, config)
        val r = engine.runWithScripts(
            MigrationCommand.UP,
            listOf(script("game", "001", "ck"), script("game", "002", "ck2")),
        )
        assertTrue(r is MigrationResult.Aborted, "got: $r")
        assertTrue(r.reason.contains("manual intervention"), "abort msg: ${r.reason}")
        assertTrue(r.reason.contains("[game]V001"), "abort msg should name failed row")
    }

    @Test
    fun up_checksumMismatch_aborts_beforePending() = runTest {
        val db = FakeMigrationDb(initialRows = listOf(history("game", "001", "history_ck")))
        val engine = MigrationEngine(db, config)
        val r = engine.runWithScripts(
            MigrationCommand.UP,
            listOf(
                script("game", "001", "disk_ck"),
                script("game", "002", "newest"), // pending
            ),
        )
        assertTrue(r is MigrationResult.Aborted, "got: $r")
        assertTrue(r.reason.contains("checksum mismatch"))
        // pending V002 should not have been applied because mismatch came first
        assertTrue(db.executedSql.none { it.contains("V002") })
    }

    @Test
    fun up_noPending_returnsUpWithSkipped() = runTest {
        val db = FakeMigrationDb(initialRows = listOf(history("game", "001", "ck1")))
        val engine = MigrationEngine(db, config)
        val r = engine.runWithScripts(
            MigrationCommand.UP,
            listOf(script("game", "001", "ck1")),
        ) as MigrationResult.Up
        assertTrue(r.ok)
        assertEquals(0, r.applied.size)
        assertEquals(1, r.skipped)
        assertNull(r.failedAt)
    }

    @Test
    fun up_appliesPending_writesHistory_inOrder() = runTest {
        val db = FakeMigrationDb(initialRows = emptyList())
        val engine = MigrationEngine(db, config)
        val r = engine.runWithScripts(
            MigrationCommand.UP,
            listOf(
                script("game", "001", "ck1"),
                script("game", "002", "ck2"),
            ),
        ) as MigrationResult.Up
        assertTrue(r.ok)
        assertEquals(2, r.applied.size)
        assertEquals("001", r.applied[0].version)
        assertEquals("002", r.applied[1].version)
        // history table should have been CREATE'd, then 2 INSERTs (post-script)
        val ddl = db.executedSql.firstOrNull { it.contains("CREATE TABLE IF NOT EXISTS") }
        assertNotNull(ddl)
        val inserts = db.executedSql.filter { it.startsWith("INSERT INTO test_migrations") }
        assertEquals(2, inserts.size)
    }

    @Test
    fun up_scriptFailure_writesFailedHistory_andReturnsFailedAt() = runTest {
        val db = FakeMigrationDb(
            initialRows = emptyList(),
            onScriptStatement = { stmt ->
                // 让 V002 的第二条业务语句失败
                if (stmt.contains("fail-me")) RuntimeException("intentional") else null
            },
        )
        val engine = MigrationEngine(db, config)
        val r = engine.runWithScripts(
            MigrationCommand.UP,
            listOf(
                script("game", "001", "ck1"),
                MigrationScript(
                    moduleId = "game", version = "002",
                    description = "boom", fileName = "V002__boom.sql",
                    absolutePath = "/fake/V002.sql",
                    content = "CREATE TABLE ok (id INT); SELECT 'fail-me';",
                    checksum = "ck2",
                ),
            ),
        ) as MigrationResult.Up
        assertEquals(false, r.ok)
        assertEquals(1, r.applied.size, "V001 should have applied before V002 failed")
        val failed = r.failedAt
        assertNotNull(failed)
        assertEquals("002", failed.version)
        assertEquals(false, failed.success)
        assertTrue(failed.errorMessage?.contains("intentional") == true)
    }

    // ============================================================
    // VERIFY 路径 (audit 点 #3)
    // ============================================================

    @Test
    fun verify_noHistoryTable_aborts() = runTest {
        val db = FakeMigrationDb(initialRows = null)
        val engine = MigrationEngine(db, config)
        val r = engine.runWithScripts(MigrationCommand.VERIFY, emptyList())
        assertTrue(r is MigrationResult.Aborted)
        assertTrue(r.reason.contains("does not exist"))
    }

    @Test
    fun verify_allMatch_returnsOk() = runTest {
        val db = FakeMigrationDb(
            initialRows = listOf(history("game", "001", "ck1"), history("game", "002", "ck2"))
        )
        val engine = MigrationEngine(db, config)
        val r = engine.runWithScripts(
            MigrationCommand.VERIFY,
            listOf(script("game", "001", "ck1"), script("game", "002", "ck2")),
        ) as MigrationResult.Verify
        assertTrue(r.ok)
        assertEquals(2, r.verifiedCount)
        assertTrue(r.mismatches.isEmpty())
        assertTrue(r.missing.isEmpty())
    }

    @Test
    fun verify_mismatchAndMissing_reportedSeparately() = runTest {
        val db = FakeMigrationDb(
            initialRows = listOf(
                history("game", "001", "history_ck"),
                history("game", "002", "ck2"),
                history("payment", "001", "pay_ck"), // disk missing
            )
        )
        val engine = MigrationEngine(db, config)
        val r = engine.runWithScripts(
            MigrationCommand.VERIFY,
            listOf(
                script("game", "001", "disk_ck"), // mismatch
                script("game", "002", "ck2"),     // ok
                // payment/001 missing on disk
            ),
        ) as MigrationResult.Verify
        assertEquals(false, r.ok)
        assertEquals(3, r.verifiedCount)
        assertEquals(1, r.mismatches.size)
        assertEquals("game", r.mismatches.first().moduleId)
        assertEquals(1, r.missing.size)
        assertEquals("payment", r.missing.first().moduleId)
    }

    @Test
    fun verify_failedHistoryRows_areExcludedFromCheck() = runTest {
        // failed rows 不参与 verify (verify 只校验已 applied 的 checksum)
        val db = FakeMigrationDb(
            initialRows = listOf(
                history("game", "001", "ck1", success = true),
                history("game", "002", "ck2", success = false, error = "boom"),
            )
        )
        val engine = MigrationEngine(db, config)
        val r = engine.runWithScripts(
            MigrationCommand.VERIFY,
            listOf(
                script("game", "001", "ck1"),
                // game/002 也磁盘上消失 — 但因为它 failed=true, verify 不应报 missing
            ),
        ) as MigrationResult.Verify
        assertTrue(r.ok, "verify ignores failed rows; mismatches=${r.mismatches} missing=${r.missing}")
        assertEquals(1, r.verifiedCount)
    }

    // ============================================================
    // 命令路径全部使用 (module_id, version) 复合键 — explicit smoke
    // ============================================================

    @Test
    fun allCommands_useCompositeKey() = runTest {
        val db = FakeMigrationDb(
            initialRows = listOf(history("a", "001", "ck"), history("b", "001", "ck"))
        )
        val scripts = listOf(script("a", "001", "ck"), script("b", "001", "ck"))

        val engine = MigrationEngine(db, config)
        // STATUS: 两条都 EXECUTED, 不会因为 version 相同而冲突
        val s = engine.runWithScripts(MigrationCommand.STATUS, scripts) as MigrationResult.Status
        assertEquals(2, s.executedCount)

        // VERIFY: 两条都 ok
        val v = engine.runWithScripts(MigrationCommand.VERIFY, scripts) as MigrationResult.Verify
        assertTrue(v.ok)

        // UP: 没有 pending
        val u = engine.runWithScripts(MigrationCommand.UP, scripts) as MigrationResult.Up
        assertTrue(u.ok)
        assertEquals(0, u.applied.size)
    }
}
