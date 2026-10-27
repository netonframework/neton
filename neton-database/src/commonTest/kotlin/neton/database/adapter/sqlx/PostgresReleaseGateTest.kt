package neton.database.adapter.sqlx

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import neton.core.module.MigrationDialect
import neton.core.module.MigrationScript
import neton.core.module.MigrationSource
import neton.database.config.DatabaseConfig
import neton.database.config.DatabaseDriver
import neton.database.migration.Checksum
import neton.database.migration.MigrationCommand
import neton.database.migration.MigrationConfig
import neton.database.migration.MigrationEngine
import neton.database.migration.MigrationResult
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PostgresReleaseGateTest {
    @Test
    fun requiredDatabaseCannotSilentlySkipOrUseExternalDatabase() {
        assertNull(postgresTestUri(null, false))
        assertFailsWith<IllegalStateException> { postgresTestUri(null, true) }
        assertFailsWith<IllegalStateException> { postgresTestUri(" ", true) }
        assertFailsWith<IllegalArgumentException> {
            postgresTestUri("postgresql://test:secret@remote:5432/neton_gate_ci", true)
        }
        assertFailsWith<IllegalArgumentException> {
            postgresTestUri("postgresql://test:secret@127.0.0.1:5432/production", true)
        }
        val local = "postgresql://test:secret@127.0.0.1:5432/neton_gate_ci"
        assertEquals(local, postgresTestUri(local, true))
    }

    @Test
    fun realPostgresMigrationsAndTransactionIsolation() = runBlocking {
        val uri = postgresTestUri() ?: return@runBlocking
        val db = SqlxDatabase.initialize(DatabaseConfig(DatabaseDriver.POSTGRESQL, uri))
        // Unique owned schema: cleanup must never drop a pre-existing test/user table.
        val suffix = Random.nextLong(1, Long.MAX_VALUE).toString(16)
        val table = "neton_gate_rows_$suffix"
        val history = "neton_gate_history_$suffix"
        val sql = "CREATE TABLE $table (id BIGINT PRIMARY KEY, value BIGINT NOT NULL);"
        val script = MigrationScript("001", "gate_rows", sql, Checksum.sha256Hex(sql.encodeToByteArray()))
        val sources = listOf(MigrationSource("neton-reference", MigrationDialect.POSTGRESQL, listOf(script)))
        val engine = MigrationEngine(db, MigrationConfig(MigrationDialect.POSTGRESQL, history))
        try {
            withTimeout(60_000) {
                val before = assertIs<MigrationResult.Status>(engine.run(MigrationCommand.STATUS, sources))
                assertEquals(1, before.pendingCount)
                val up = assertIs<MigrationResult.Up>(engine.run(MigrationCommand.UP, sources))
                assertTrue(up.ok)
                assertEquals(1, up.applied.size)
                val again = assertIs<MigrationResult.Up>(engine.run(MigrationCommand.UP, sources))
                assertTrue(again.ok)
                assertEquals(0, again.applied.size)
                val status = assertIs<MigrationResult.Status>(engine.run(MigrationCommand.STATUS, sources))
                assertEquals(listOf(MigrationResult.ScriptState.EXECUTED), status.scripts.map { it.state })
                val verified = assertIs<MigrationResult.Verify>(engine.run(MigrationCommand.VERIFY, sources))
                assertTrue(verified.ok)
                assertEquals(1, verified.verifiedCount)

                // Changing embedded SQL must be rejected, not silently accepted on a new build.
                val changedSql = "$sql\n-- changed"
                val changed = listOf(sources.single().copy(scripts = listOf(script.copy(
                    content = changedSql, checksum = Checksum.sha256Hex(changedSql.encodeToByteArray()),
                ))))
                assertEquals(1, assertIs<MigrationResult.Status>(engine.run(MigrationCommand.STATUS, changed)).mismatchCount)
                assertTrue(!assertIs<MigrationResult.Verify>(engine.run(MigrationCommand.VERIFY, changed)).ok)
                assertIs<MigrationResult.Aborted>(engine.run(MigrationCommand.UP, changed))

                db.transaction {
                    execute("INSERT INTO $table VALUES (:id, :value)", mapOf("id" to 1L, "value" to 10L))
                }
                assertFailsWith<RollbackProbe> {
                    db.transaction {
                        execute("INSERT INTO $table VALUES (2, 20)")
                        transaction { execute("INSERT INTO $table VALUES (3, 30)") }
                        throw RollbackProbe()
                    }
                }
                assertEquals(listOf(1L), db.fetchAll("SELECT id FROM $table ORDER BY id").map { it.long("id") })

                assertFailsWith<CancellationException> {
                    db.transaction {
                        execute("INSERT INTO $table VALUES (6, 60)")
                        throw CancellationException("intentional gate cancellation")
                    }
                }
                assertEquals(listOf(1L), db.fetchAll("SELECT id FROM $table ORDER BY id").map { it.long("id") })

                // Both transactions are open simultaneously. One commits, the other rolls back.
                // A shared/cross-coroutine transaction would leak or roll back the committed row.
                coroutineScope {
                    val firstReady = CompletableDeferred<Unit>()
                    val secondReady = CompletableDeferred<Unit>()
                    val committed = async {
                        db.transaction {
                            execute("INSERT INTO $table VALUES (4, 40)")
                            firstReady.complete(Unit)
                            secondReady.await()
                        }
                    }
                    val rolledBack = async {
                        assertFailsWith<RollbackProbe> {
                            db.transaction {
                                execute("INSERT INTO $table VALUES (5, 50)")
                                secondReady.complete(Unit)
                                firstReady.await()
                                throw RollbackProbe()
                            }
                        }
                    }
                    committed.await()
                    rolledBack.await()
                }
                assertEquals(listOf(1L, 4L), db.fetchAll("SELECT id FROM $table ORDER BY id").map { it.long("id") })
                assertTrue(!db.inTransaction())
                println("NETON_POSTGRES_TRANSACTIONS_EXECUTED")
            }
        } finally {
            try {
                db.execute("DROP TABLE IF EXISTS $table")
                db.execute("DROP TABLE IF EXISTS $history")
            } finally {
                SqlxDatabase.close()
            }
        }
    }

    private class RollbackProbe : RuntimeException("intentional gate rollback")
}
