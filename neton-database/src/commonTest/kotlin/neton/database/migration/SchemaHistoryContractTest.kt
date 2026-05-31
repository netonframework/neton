package neton.database.migration

import neton.core.module.MigrationDialect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * DB-MIG-2 — Schema history contract frozen.
 *
 * Golden tests 锁死 DDL bit-for-bit;state matrix 覆盖 5 个状态 × 优先级。
 * 任何 DDL / 状态语义改动必须先更新这些测试。
 */
class SchemaHistoryContractTest {

    // ============================================================
    // DDL goldens (三方言 byte-exact, audit 点 #1)
    // ============================================================

    @Test
    fun ddl_sqlite_golden() {
        val expected = """
            CREATE TABLE IF NOT EXISTS neton_schema_history (
              module_id     TEXT NOT NULL,
              version       TEXT NOT NULL,
              description   TEXT,
              checksum      TEXT,
              installed_at  INTEGER NOT NULL,
              execution_ms  INTEGER NOT NULL,
              success       INTEGER NOT NULL,
              error_message TEXT,
              UNIQUE(module_id, version)
            )
        """.trimIndent()
        assertEquals(expected, historyTableDdl(MigrationDialect.SQLITE, "neton_schema_history"))
    }

    @Test
    fun ddl_postgresql_golden() {
        val expected = """
            CREATE TABLE IF NOT EXISTS neton_schema_history (
              module_id     VARCHAR(64)  NOT NULL,
              version       VARCHAR(32)  NOT NULL,
              description   VARCHAR(255),
              checksum      VARCHAR(128),
              installed_at  BIGINT       NOT NULL,
              execution_ms  BIGINT       NOT NULL,
              success       BOOLEAN      NOT NULL,
              error_message TEXT,
              UNIQUE(module_id, version)
            )
        """.trimIndent()
        assertEquals(expected, historyTableDdl(MigrationDialect.POSTGRESQL, "neton_schema_history"))
    }

    @Test
    fun ddl_mysql_golden() {
        val expected = """
            CREATE TABLE IF NOT EXISTS neton_schema_history (
              module_id     VARCHAR(64)  NOT NULL,
              version       VARCHAR(32)  NOT NULL,
              description   VARCHAR(255),
              checksum      VARCHAR(128),
              installed_at  BIGINT       NOT NULL,
              execution_ms  BIGINT       NOT NULL,
              success       TINYINT(1)   NOT NULL,
              error_message TEXT,
              UNIQUE (module_id, version)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin
        """.trimIndent()
        assertEquals(expected, historyTableDdl(MigrationDialect.MYSQL, "neton_schema_history"))
    }

    /**
     * MySQL 索引名长度回归 — 之前 named index `uq_${table}_module_version` 在 table
     * 接近 63 char 上限时超过 MySQL 64 char identifier 限制。修复后改成行内 UNIQUE,
     * 永远不引入 named index。任何 table 长度都安全。
     */
    @Test
    fun ddl_mysql_longTableName_doesNotProduceNamedIndex() {
        val longTable = "a".repeat(63) // validateTableName 上限
        val ddl = historyTableDdl(MigrationDialect.MYSQL, longTable)
        // 行内 UNIQUE, 不应出现 "UNIQUE KEY <name>"
        assertFalse(
            ddl.contains("UNIQUE KEY"),
            "MySQL DDL must not use named UNIQUE KEY (overflow risk); got: $ddl",
        )
        assertTrue(ddl.contains("UNIQUE (module_id, version)"))
    }

    /**
     * 表名参数化贯彻三方言: 不允许任一方言里出现硬编码 "neton_schema_history"。
     */
    @Test
    fun ddl_tableName_isParameterized_acrossDialects() {
        val custom = "privchat_migrations"
        for (d in MigrationDialect.values()) {
            val ddl = historyTableDdl(d, custom)
            assertTrue(ddl.contains("CREATE TABLE IF NOT EXISTS $custom"), "$d missing parameterized table")
            assertFalse(ddl.contains("neton_schema_history"), "$d hardcoded default name leaked")
        }
    }

    // ============================================================
    // Boolean literal (三方言原生)
    // ============================================================

    @Test
    fun boolLiteral_threeDialects() {
        assertEquals("TRUE", historyBoolLiteral(MigrationDialect.POSTGRESQL, true))
        assertEquals("FALSE", historyBoolLiteral(MigrationDialect.POSTGRESQL, false))
        assertEquals("1", historyBoolLiteral(MigrationDialect.MYSQL, true))
        assertEquals("0", historyBoolLiteral(MigrationDialect.MYSQL, false))
        assertEquals("1", historyBoolLiteral(MigrationDialect.SQLITE, true))
        assertEquals("0", historyBoolLiteral(MigrationDialect.SQLITE, false))
    }

    @Test
    fun escapeSqlString_doublesSingleQuotes() {
        assertEquals("abc", escapeSqlString("abc"))
        assertEquals("don''t", escapeSqlString("don't"))
        assertEquals("''", escapeSqlString("'"))
        assertEquals("a'' OR ''1''=''1", escapeSqlString("a' OR '1'='1"))
    }
}
