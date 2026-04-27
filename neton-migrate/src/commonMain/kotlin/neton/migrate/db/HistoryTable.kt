package neton.migrate.db

import neton.migrate.config.Driver

/**
 * neton_schema_history 表的 schema 定义、CRUD、与三方言 DDL 适配。
 *
 * 字段（与 spec §6.2 对齐）：
 *   version       VARCHAR(50) PRIMARY KEY
 *   description   VARCHAR(200)
 *   script        VARCHAR(255)
 *   checksum      VARCHAR(64)
 *   executed_at   BIGINT      (毫秒时间戳)
 *   duration_ms   BIGINT
 *   success       INT         (0/1) — 用 INT 兼容 SQLite/MySQL/PG
 *   error_message TEXT NULL
 */
object HistoryTable {

    data class Row(
        val version: String,
        val description: String,
        val script: String,
        val checksum: String,
        val executedAt: Long,
        val durationMs: Long,
        val success: Boolean,
        val errorMessage: String?
    )

    /**
     * status 不应创建表（决策额外冻结 #2 — read only）；
     * up 时调用此方法保证表存在。
     */
    suspend fun ensureExists(db: DbConnection, table: String) {
        db.execute(ddl(db.driver, table))
    }

    /**
     * 探测表是否存在 — 通过查 information_schema / sqlite_master / pg_tables。
     */
    suspend fun exists(db: DbConnection, table: String): Boolean {
        val sql = when (db.driver) {
            Driver.SQLITE ->
                "SELECT name FROM sqlite_master WHERE type='table' AND name='$table' LIMIT 1"
            Driver.POSTGRESQL ->
                "SELECT tablename FROM pg_tables WHERE tablename='$table' LIMIT 1"
            Driver.MYSQL ->
                "SELECT table_name FROM information_schema.tables " +
                "WHERE table_schema = DATABASE() AND table_name = '$table' LIMIT 1"
        }
        val rows = db.queryRows(sql, listOf(when (db.driver) {
            Driver.SQLITE -> "name"
            Driver.POSTGRESQL -> "tablename"
            Driver.MYSQL -> "table_name"
        }))
        return rows.isNotEmpty()
    }

    suspend fun listAll(db: DbConnection, table: String): List<Row> {
        val sql = """
            SELECT version, description, script, checksum,
                   executed_at, duration_ms, success, error_message
            FROM $table
            ORDER BY version ASC
        """.trimIndent()
        val cols = listOf(
            "version", "description", "script", "checksum",
            "executed_at", "duration_ms", "success", "error_message"
        )
        return db.queryRows(sql, cols).map { m ->
            Row(
                version = m["version"] ?: "",
                description = m["description"] ?: "",
                script = m["script"] ?: "",
                checksum = m["checksum"] ?: "",
                executedAt = m["executed_at"]?.toLongOrNull() ?: 0L,
                durationMs = m["duration_ms"]?.toLongOrNull() ?: 0L,
                success = (m["success"]?.toIntOrNull() ?: 0) == 1,
                errorMessage = m["error_message"]
            )
        }
    }

    suspend fun insert(db: DbConnection, table: String, row: Row) {
        // v0.1 不用 prepared statement —— history 表写入次数低，
        // 字段值由本程序构造，escapeSql 即可防注入。
        val sql = """
            INSERT INTO $table
              (version, description, script, checksum, executed_at, duration_ms, success, error_message)
            VALUES
              ('${esc(row.version)}', '${esc(row.description)}', '${esc(row.script)}',
               '${esc(row.checksum)}', ${row.executedAt}, ${row.durationMs},
               ${if (row.success) 1 else 0},
               ${row.errorMessage?.let { "'${esc(it)}'" } ?: "NULL"})
        """.trimIndent()
        db.execute(sql)
    }

    private fun esc(s: String): String = s.replace("'", "''")

    private fun ddl(driver: Driver, table: String): String = when (driver) {
        Driver.SQLITE -> """
            CREATE TABLE IF NOT EXISTS $table (
              version       TEXT PRIMARY KEY,
              description   TEXT NOT NULL,
              script        TEXT NOT NULL,
              checksum      TEXT NOT NULL,
              executed_at   INTEGER NOT NULL,
              duration_ms   INTEGER NOT NULL,
              success       INTEGER NOT NULL,
              error_message TEXT
            )
        """.trimIndent()

        Driver.POSTGRESQL -> """
            CREATE TABLE IF NOT EXISTS $table (
              version       VARCHAR(50)  PRIMARY KEY,
              description   VARCHAR(200) NOT NULL,
              script        VARCHAR(255) NOT NULL,
              checksum      VARCHAR(64)  NOT NULL,
              executed_at   BIGINT       NOT NULL,
              duration_ms   BIGINT       NOT NULL,
              success       INT          NOT NULL,
              error_message TEXT
            )
        """.trimIndent()

        Driver.MYSQL -> """
            CREATE TABLE IF NOT EXISTS $table (
              version       VARCHAR(50)  PRIMARY KEY,
              description   VARCHAR(200) NOT NULL,
              script        VARCHAR(255) NOT NULL,
              checksum      VARCHAR(64)  NOT NULL,
              executed_at   BIGINT       NOT NULL,
              duration_ms   BIGINT       NOT NULL,
              success       TINYINT      NOT NULL,
              error_message TEXT
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """.trimIndent()
    }
}
