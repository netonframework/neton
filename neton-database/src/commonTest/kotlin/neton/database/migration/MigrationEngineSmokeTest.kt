package neton.database.migration

import neton.core.module.MigrationDialect
import neton.core.module.MigrationSource
import neton.database.config.DatabaseDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * DB-MIG-1 smoke: 纯单元测试,覆盖 engine 的 driver-agnostic 组件。
 * Engine 端到端(DbContext + 真实 sqlite memory)留给后续 DB-MIG-4 / DB-MIG-7。
 */
class MigrationEngineSmokeTest {

    // ============================================================
    // MigrationConfig
    // ============================================================

    @Test
    fun config_defaultHistoryTable_isNetonSchemaHistory() {
        assertEquals("neton_schema_history", MigrationConfig.DEFAULT_HISTORY_TABLE)
        val cfg = MigrationConfig(dialect = MigrationDialect.POSTGRESQL)
        assertEquals("neton_schema_history", cfg.historyTable)
    }

    @Test
    fun config_customHistoryTable_isHeld() {
        val cfg = MigrationConfig(
            dialect = MigrationDialect.POSTGRESQL,
            historyTable = "privchat_migrations",
        )
        assertEquals("privchat_migrations", cfg.historyTable)
    }

    // ============================================================
    // MigrationDialect
    // ============================================================

    @Test
    fun dialect_fromString_canonicalAndAliases() {
        assertEquals(MigrationDialect.POSTGRESQL, MigrationDialect.fromString("postgresql"))
        assertEquals(MigrationDialect.POSTGRESQL, MigrationDialect.fromString("postgres"))
        assertEquals(MigrationDialect.POSTGRESQL, MigrationDialect.fromString("PG"))
        assertEquals(MigrationDialect.MYSQL, MigrationDialect.fromString("mysql"))
        assertEquals(MigrationDialect.SQLITE, MigrationDialect.fromString("sqlite"))
        assertEquals(null, MigrationDialect.fromString("oracle"))
    }

    @Test
    fun dialect_fromDriver_memoryMapsToSqlite() {
        assertEquals(MigrationDialect.POSTGRESQL, MigrationDialect.Companion.fromDriver(DatabaseDriver.POSTGRESQL))
        assertEquals(MigrationDialect.MYSQL, MigrationDialect.Companion.fromDriver(DatabaseDriver.MYSQL))
        assertEquals(MigrationDialect.SQLITE, MigrationDialect.Companion.fromDriver(DatabaseDriver.SQLITE))
        assertEquals(MigrationDialect.SQLITE, MigrationDialect.Companion.fromDriver(DatabaseDriver.MEMORY))
    }

    // ============================================================
    // SchemaHistoryRepository.validateTableName
    // ============================================================

    @Test
    fun historyTableName_accepts_validIdentifiers() {
        assertEquals("neton_schema_history", SchemaHistoryRepository.validateTableName("neton_schema_history"))
        assertEquals("privchat_migrations", SchemaHistoryRepository.validateTableName("privchat_migrations"))
        assertEquals("_t", SchemaHistoryRepository.validateTableName("_t"))
        assertEquals("ABc_123", SchemaHistoryRepository.validateTableName("ABc_123"))
    }

    @Test
    fun historyTableName_rejects_injectionAttempts() {
        assertFailsWith<IllegalArgumentException> {
            SchemaHistoryRepository.validateTableName("t1;DROP TABLE users;--")
        }
        assertFailsWith<IllegalArgumentException> {
            SchemaHistoryRepository.validateTableName("t1 t2")
        }
        assertFailsWith<IllegalArgumentException> {
            SchemaHistoryRepository.validateTableName("1starts_with_digit")
        }
        assertFailsWith<IllegalArgumentException> {
            SchemaHistoryRepository.validateTableName("")
        }
        assertFailsWith<IllegalArgumentException> {
            SchemaHistoryRepository.validateTableName("\"injected\"")
        }
    }

    // ============================================================
    // MigrationSqlSplitter — 多语句切分(BLOCKER-3 核心防回归)
    // ============================================================

    @Test
    fun splitter_multipleSimpleStatements() {
        val sql = """
            CREATE TABLE a (id INT);
            CREATE TABLE b (id INT);
            INSERT INTO a VALUES (1);
        """.trimIndent()
        val parts = MigrationSqlSplitter.split(sql)
        assertEquals(3, parts.size)
        assertTrue(parts[0].startsWith("CREATE TABLE a"))
        assertTrue(parts[1].startsWith("CREATE TABLE b"))
        assertTrue(parts[2].startsWith("INSERT INTO a"))
    }

    @Test
    fun splitter_semicolonInsideStringLiteral_isNotBoundary() {
        val sql = "INSERT INTO t VALUES ('a;b;c'); SELECT 1;"
        val parts = MigrationSqlSplitter.split(sql)
        assertEquals(2, parts.size)
        assertEquals("INSERT INTO t VALUES ('a;b;c')", parts[0])
        assertEquals("SELECT 1", parts[1])
    }

    @Test
    fun splitter_pgDollarQuoted_preservesSemicolons() {
        val sql = """
            CREATE FUNCTION f() RETURNS void AS ${'$'}${'$'}
              BEGIN INSERT INTO t VALUES (1); INSERT INTO t VALUES (2); END;
            ${'$'}${'$'} LANGUAGE plpgsql;
            SELECT 1;
        """.trimIndent()
        val parts = MigrationSqlSplitter.split(sql)
        assertEquals(2, parts.size)
        assertTrue(parts[0].startsWith("CREATE FUNCTION"))
        assertTrue(parts[0].contains("LANGUAGE plpgsql"))
        assertEquals("SELECT 1", parts[1])
    }

    @Test
    fun splitter_lineCommentDoesNotEatTrailingStatement() {
        val sql = """
            -- create the first table
            CREATE TABLE a (id INT);
            -- second
            CREATE TABLE b (id INT);
        """.trimIndent()
        val parts = MigrationSqlSplitter.split(sql)
        assertEquals(2, parts.size)
    }

    @Test
    fun splitter_pureCommentScript_yieldsEmpty() {
        val sql = "-- just a comment\n/* and a block */"
        assertEquals(emptyList<String>(), MigrationSqlSplitter.split(sql))
    }

    // ============================================================
    // Checksum
    // ============================================================

    @Test
    fun checksum_emptyInput_isKnownSha256() {
        // SHA-256 of empty string
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Checksum.sha256Hex(ByteArray(0)),
        )
    }

    @Test
    fun checksum_abc_isKnownSha256() {
        // SHA-256("abc")
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Checksum.sha256Hex("abc".encodeToByteArray()),
        )
    }

    @Test
    fun checksum_singleByteChange_changesDigest() {
        val a = Checksum.sha256Hex("CREATE TABLE users (id INT);".encodeToByteArray())
        val b = Checksum.sha256Hex("CREATE TABLE Users (id INT);".encodeToByteArray())
        assertTrue(a != b, "checksum must change with single-byte content change")
    }

    // ============================================================
    // MigrationResult.Status counts
    // ============================================================

    @Test
    fun status_counts_aggregateCorrectly() {
        val views = listOf(
            view("game", "001", MigrationResult.ScriptState.EXECUTED),
            view("game", "002", MigrationResult.ScriptState.PENDING),
            view("game", "003", MigrationResult.ScriptState.PENDING),
            view("payment", "001", MigrationResult.ScriptState.CHECKSUM_MISMATCH),
            view("payment", "002", MigrationResult.ScriptState.FAILED),
        )
        val s = MigrationResult.Status(
            historyTable = "neton_schema_history",
            historyExists = true,
            scripts = views,
            warnings = emptyList(),
        )
        assertEquals(1, s.executedCount)
        assertEquals(2, s.pendingCount)
        assertEquals(1, s.mismatchCount)
        assertEquals(1, s.failedCount)
    }

    private fun view(module: String, version: String, state: MigrationResult.ScriptState) =
        MigrationResult.ScriptView(
            moduleId = module,
            version = version,
            description = "test",
            state = state,
        )

    // ============================================================
    // MigrationSource / MigrationScript data class sanity
    // ============================================================

    @Test
    fun migrationSource_holdsFields() {
        val s = MigrationSource(
            moduleId = "privchat-application",
            dialect = MigrationDialect.POSTGRESQL,
            resourcePath = "sql/postgresql",
        )
        assertEquals("privchat-application", s.moduleId)
        assertEquals(MigrationDialect.POSTGRESQL, s.dialect)
        assertEquals("sql/postgresql", s.resourcePath)
    }

    @Test
    fun migrationScript_holdsFields() {
        val s = MigrationScript(
            moduleId = "game",
            version = "001",
            description = "create_tables",
            fileName = "V001__create_tables.sql",
            absolutePath = "/tmp/sql/postgresql/V001__create_tables.sql",
            content = "CREATE TABLE t (id INT);",
            checksum = "abc",
        )
        assertEquals("001", s.version)
        assertEquals("create_tables", s.description)
        assertEquals("abc", s.checksum)
    }
}
